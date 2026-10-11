package com.musicrec.evaluation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.musicrec.audio.*;
import com.musicrec.dto.ConversationTurnDTO;
import com.musicrec.dto.TranscriptSegmentDTO;
import com.musicrec.evaluation.AudioEvaluationReport.RecordingResult;
import com.musicrec.evaluation.AudioEvaluationReport.ResponseResult;
import com.musicrec.service.EmotionAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Evaluates the AUDIO side of the conversation pipeline on labelled recordings
 * (evaluation/audio/*.json): for every recording, decode → speech-emotion service (voice emotion +
 * voiceprints per labelled turn) → speaker separation by pitch and by voiceprint, scored against the
 * true roles → per patient response, emotion from the words (one LLM call per recording), from the
 * voice, and fused at several voice weights, scored against the gold emotion.
 *
 * Turn boundaries come from the labels, not from speech-to-text, so the separation and emotion methods
 * are measured on their own. Synthetic (text-to-speech) recordings are flagged in the report: they are
 * fine for checking the harness, but not evidence of how the voice model does on real speech.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AudioEvaluationService {

    static final double[] VOICE_WEIGHTS = {0.0, 0.3, 0.5, 1.0};

    private final AudioTranscodingService audioTranscodingService;
    private final AcousticFeatureExtractor acousticFeatureExtractor;
    private final SpeakerDiarizationService speakerDiarizationService;
    private final SpeechEmotionClient speechEmotionClient;
    private final EmotionAnalysisService emotionAnalysisService;
    private final ObjectMapper objectMapper;

    @Value("${app.evaluation.results-dir:../evaluation/results}")
    private String resultsDir;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AudioDataset(String name, String source, String description, List<Recording> recordings) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recording(String id, String file, List<Turn> turns) {}

    /** @param emotion gold label for a PATIENT turn (canonical emotion), null for consultant turns */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Turn(String role, long startMs, long endMs, String text, String emotion) {}

    public AudioEvaluationReport run(String manifestPath) throws IOException {
        Path manifest = Path.of(manifestPath);
        AudioDataset ds = objectMapper.readValue(manifest.toFile(), AudioDataset.class);
        Path baseDir = manifest.toAbsolutePath().getParent();

        List<RecordingResult> results = new ArrayList<>();
        for (Recording rec : ds.recordings()) {
            try {
                results.add(evaluate(rec, baseDir));
                log.info("AUDIO EVALUATION: recording {} done", rec.id());
            } catch (IllegalStateException ex) {
                throw ex;
            } catch (Exception ex) {
                log.warn("AUDIO EVALUATION: recording {} failed: {}", rec.id(), ex.getMessage());
                results.add(new RecordingResult(rec.id(), ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                        rec.turns().size(), 0, false, 0, false, List.of()));
            }
        }
        AudioEvaluationReport report = aggregate(ds, results);
        write(report);
        return report;
    }

    private RecordingResult evaluate(Recording rec, Path baseDir) throws IOException {
        Path file = baseDir.resolve(rec.file());
        if (!Files.exists(file)) throw new IOException("audio file not found: " + rec.file());
        short[] pcm = audioTranscodingService.toPcm16Mono16k(Files.readAllBytes(file), rec.file());
        List<AcousticFeatureExtractor.Frame> frames = acousticFeatureExtractor.analyze(pcm);

        List<TranscriptSegmentDTO> segments = new ArrayList<>();
        for (int i = 0; i < rec.turns().size(); i++) {
            Turn t = rec.turns().get(i);
            segments.add(new TranscriptSegmentDTO(i, t.startMs(), t.endMs(), t.text() == null ? "" : t.text()));
        }
        SpeechEmotionClient.Result voice = speechEmotionClient.analyze(pcm,
                        segments.stream().map(s -> new long[]{s.startMs(), s.endMs()}).toList(), true)
                .orElseThrow(() -> new IllegalStateException("The speech-emotion service is not reachable — start "
                        + "ser-service (see ser-service/README.md) before running the audio evaluation."));

        // ── Speaker separation ──
        List<String> roles = rec.turns().stream().map(Turn::role).toList();
        var pitch = speakerDiarizationService.diarize(frames, segments);
        var voiceprint = speakerDiarizationService.diarizeByEmbeddings(segments,
                voice.segments().stream().map(SpeechEmotionClient.SegmentAnalysis::embedding).toList(), frames);

        // ── Emotion per patient response ──
        List<Integer> patient = new ArrayList<>();
        for (int i = 0; i < rec.turns().size(); i++) {
            if (ConversationTurnDTO.PATIENT.equalsIgnoreCase(rec.turns().get(i).role())) patient.add(i);
        }
        List<EmotionAnalysisService.SegmentEmotion> text = patient.isEmpty() ? List.of()
                : emotionAnalysisService.analyzeBatch(patient.stream().map(i -> segments.get(i).text()).toList());

        List<ResponseResult> responses = new ArrayList<>();
        for (int k = 0; k < patient.size(); k++) {
            int i = patient.get(k);
            EmotionAnalysisService.SegmentEmotion te = k < text.size() ? text.get(k) : null;
            String textLabel = te == null ? "RELAXATION" : te.primaryEmotion();
            double textConf = te == null ? 0.2 : te.confidence();
            SpeechEmotionClient.SpeechEmotion ve = i < voice.segments().size() ? voice.segments().get(i).emotion() : null;
            Map<String, String> fused = new LinkedHashMap<>();
            for (double w : VOICE_WEIGHTS) {
                fused.put(String.valueOf(w), SpeechEmotionFusion.fuse(textLabel, textConf, ve, w).label());
            }
            responses.add(new ResponseResult(segments.get(i).text(), upper(rec.turns().get(i).emotion()), textLabel, textConf,
                    ve == null ? null : ve.label(), ve == null ? null : ve.confidence(), fused));
        }

        return new RecordingResult(rec.id(), null, rec.turns().size(),
                AudioEvaluationScoring.roleAccuracy(clusters(pitch), roles), pitch.reliable(),
                AudioEvaluationScoring.roleAccuracy(clusters(voiceprint), roles), voiceprint.reliable(), responses);
    }

    private AudioEvaluationReport aggregate(AudioDataset ds, List<RecordingResult> all) {
        List<RecordingResult> done = all.stream().filter(r -> r.error() == null).toList();
        List<ResponseResult> responses = done.stream().flatMap(r -> r.responses().stream()).toList();

        List<String> gold = responses.stream().map(ResponseResult::goldEmotion).toList();
        List<String> goldVoice = gold.stream().map(AudioEvaluationScoring::voiceClass).toList();
        List<String> voicePred = responses.stream().map(ResponseResult::voiceEmotion).toList();
        List<String> goldVoiceScored = new ArrayList<>();
        for (int i = 0; i < goldVoice.size(); i++) goldVoiceScored.add(voicePred.get(i) == null ? null : goldVoice.get(i));

        Map<String, Double> fusedAcc = new LinkedHashMap<>();
        for (double w : VOICE_WEIGHTS) {
            String key = String.valueOf(w);
            fusedAcc.put(key, AudioEvaluationScoring.accuracy(
                    responses.stream().map(r -> r.fusedByWeight().get(key)).toList(), gold));
        }
        int textFallback = (int) responses.stream()
                .filter(r -> r.textConfidence() <= EmotionAnalysisService.KEYWORD_FALLBACK_CONFIDENCE).count();

        List<String> notes = new ArrayList<>();
        if ("synthetic-tts".equalsIgnoreCase(ds.source())) {
            notes.add("These recordings are synthetic (text-to-speech). They check that the audio pipeline and this "
                    + "evaluation work; they are NOT evidence of how the voice-emotion model or voiceprints perform on "
                    + "real human speech. Record real conversations (with consent) for that — see evaluation/audio/README.md.");
        }
        notes.add("Voice-only accuracy compares the voice model's 4 classes (neutral/happy/angry/sad) with the gold "
                + "emotion collapsed onto them; responses whose gold emotion has no voice class (NOSTALGIA, STRESS, "
                + "ANXIETY, LOVE) are excluded — " + goldVoiceScored.stream().filter(Objects::nonNull).count()
                + " of " + responses.size() + " responses were scorable.");
        notes.add("Speaker separation is scored on the labelled turn boundaries, under the better cluster-to-role mapping.");
        if (textFallback > 0) {
            notes.add(textFallback + " response(s) had their text emotion from the keyword fallback (LLM unavailable) — "
                    + "text-only and fused numbers do not reflect the LLM for those. Re-run.");
        }

        return new AudioEvaluationReport(ds.name(), ds.source(), LocalDateTime.now().toString(),
                all.size(), done.size(),
                mean(done.stream().mapToDouble(RecordingResult::pitchRoleAccuracy)),
                mean(done.stream().mapToDouble(r -> r.pitchReliable() ? 1 : 0)),
                mean(done.stream().mapToDouble(RecordingResult::voiceprintRoleAccuracy)),
                mean(done.stream().mapToDouble(r -> r.voiceprintReliable() ? 1 : 0)),
                AudioEvaluationScoring.accuracy(responses.stream().map(ResponseResult::textEmotion).toList(), gold),
                AudioEvaluationScoring.accuracy(voicePred, goldVoiceScored),
                (int) goldVoiceScored.stream().filter(Objects::nonNull).count(),
                responses.size(), fusedAcc, textFallback, all, notes);
    }

    private void write(AudioEvaluationReport r) throws IOException {
        Path dir = Path.of(resultsDir);
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(dir.resolve("audio-eval-" + stamp + ".json").toFile(), r);
        Files.writeString(dir.resolve("audio-eval-" + stamp + ".md"), markdown(r), StandardCharsets.UTF_8);
    }

    private static String markdown(AudioEvaluationReport r) {
        StringBuilder sb = new StringBuilder("# Audio evaluation report: ").append(r.name()).append("\n\n")
                .append("Run at ").append(r.runAt()).append(" · source `").append(r.source()).append("` · ")
                .append(r.recordingsCompleted()).append(" of ").append(r.recordingsTotal()).append(" recordings · ")
                .append(r.patientResponses()).append(" patient responses\n\n");
        sb.append("## Speaker separation\n\n| Method | Role accuracy | Reported reliable |\n|---|---|---|\n")
          .append("| Pitch / energy clustering | ").append(pct(r.pitchRoleAccuracy())).append(" | ").append(pct(r.pitchReliableRate())).append(" |\n")
          .append("| Voiceprint (WavLM embeddings) | ").append(pct(r.voiceprintRoleAccuracy())).append(" | ").append(pct(r.voiceprintReliableRate())).append(" |\n\n");
        sb.append("## Emotion per patient response\n\n| Source | Accuracy |\n|---|---|\n")
          .append("| Words only (LLM, 12 classes) | ").append(pct(r.textOnlyAccuracy())).append(" |\n")
          .append("| Voice only (speech-emotion model, 4 classes, ").append(r.voiceScoredResponses()).append(" scorable) | ")
          .append(pct(r.voiceOnlyAccuracy())).append(" |\n");
        r.fusedAccuracyByWeight().forEach((w, a) -> sb.append("| Fused, voice weight ").append(w).append(" | ").append(pct(a)).append(" |\n"));
        sb.append("\n## Recordings\n\n| Recording | Pitch role acc. | Voiceprint role acc. | Responses (gold → words / voice / fused 0.3) |\n|---|---|---|---|\n");
        for (RecordingResult rec : r.recordings()) {
            if (rec.error() != null) { sb.append("| ").append(rec.id()).append(" | — | — | failed: ").append(rec.error()).append(" |\n"); continue; }
            StringBuilder resp = new StringBuilder();
            for (ResponseResult x : rec.responses()) {
                resp.append(x.goldEmotion()).append(" → ").append(x.textEmotion()).append(" / ").append(x.voiceEmotion())
                    .append(" / ").append(x.fusedByWeight().get("0.3")).append("; ");
            }
            sb.append("| ").append(rec.id()).append(" | ").append(pct(rec.pitchRoleAccuracy())).append(rec.pitchReliable() ? "" : " (uncertain)")
              .append(" | ").append(pct(rec.voiceprintRoleAccuracy())).append(rec.voiceprintReliable() ? "" : " (uncertain)")
              .append(" | ").append(resp).append(" |\n");
        }
        sb.append("\n## Notes\n\n");
        r.notes().forEach(n -> sb.append("- ").append(n).append("\n"));
        return sb.toString();
    }

    private static int[] clusters(SpeakerDiarizationService.DiarizationResult r) {
        return r.segments().stream().mapToInt(SpeakerDiarizationService.DiarizedSegment::clusterId).toArray();
    }

    private static double mean(java.util.stream.DoubleStream s) {
        return EvaluationScoring.round(s.average().orElse(0));
    }

    private static String upper(String s) {
        return s == null ? null : s.strip().toUpperCase();
    }

    private static String pct(Double v) {
        return v == null ? "n/a" : String.format("%.1f%%", v * 100);
    }
}
