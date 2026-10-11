package com.musicrec.controller;

import com.musicrec.audio.AcousticFeatureExtractor;
import com.musicrec.audio.AudioTranscodingService;
import com.musicrec.audio.SpeakerDiarizationService;
import com.musicrec.audio.SpeechEmotionClient;
import com.musicrec.audio.SpeechEmotionFusion;
import org.springframework.beans.factory.annotation.Value;
import com.musicrec.dto.ConversationRecommendationResponse;
import com.musicrec.dto.ConversationTurnDTO;
import com.musicrec.dto.RoleReassignmentRequest;
import com.musicrec.exception.ConversationProcessingException;
import com.musicrec.exception.InvalidAudioException;
import com.musicrec.dto.TranscriptSegmentDTO;
import com.musicrec.service.ConversationAnalysisService;
import com.musicrec.service.ConversationPipelineService;
import com.musicrec.service.SpeakerRoleResolver;
import com.musicrec.service.SpeechToTextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Conversation-based, Raga-centric recommendation pipeline:
 *
 * Conversation Audio → Segmented Speech-to-Text (real timestamps)
 *   → Audio-based Speaker Diarization (acoustic clustering) + Text-based Role Labeling
 *   → [optional user correction via /reassign-roles]
 *   → ConversationPipelineService: Patient-only Segment Emotion Analysis (text + acoustic arousal)
 *     → Emotional-Variability Profile → Raga Selection → Hindi-only Raga-Based Candidate Songs
 *     → Top-N split across the Patient's emotions by share → Ranked Recommendations
 *
 * This is additive: it does not remove /recommend/text or /recommend/voice. The
 * Hindi-only restriction (req. #2) is now enforced system-wide (see
 * MusicRecommendationService), and real audio-based speaker diarization +
 * acoustic emotion analysis apply here specifically.
 */
@RestController
@RequestMapping("/api/v1/conversation")
@RequiredArgsConstructor
@Slf4j
public class ConversationController {

    private static final long MAX_AUDIO_BYTES = 25L * 1024 * 1024; // 25MB — Groq Whisper's own limit
    private static final Set<String> ACCEPTED_CONTENT_PREFIXES = Set.of("audio/", "video/webm");

    private final SpeechToTextService speechToTextService;
    private final ConversationAnalysisService conversationAnalysisService;
    private final SpeakerRoleResolver speakerRoleResolver;
    private final AudioTranscodingService audioTranscodingService;
    private final AcousticFeatureExtractor acousticFeatureExtractor;
    private final SpeakerDiarizationService speakerDiarizationService;
    private final ConversationPipelineService conversationPipelineService;
    private final SpeechEmotionClient speechEmotionClient;

    /** Voice weight when fusing the speech-emotion model with the words (0 = words only, 1 = voice only). */
    @Value("${app.ser.audio-weight:0.3}")
    private double audioWeight;

    private static final String SER_MODEL = "superb/wav2vec2-base-superb-er speech emotion model";

    /** Decoded upload: raw 16 kHz PCM (null when it couldn't be decoded locally) and its acoustic frames. */
    private record DecodedAudio(short[] pcm, List<AcousticFeatureExtractor.Frame> frames) {}

    /**
     * Accepts a recorded conversation audio file (Health Consultant + Patient) and runs
     * the full pipeline through to a Raga-based, explainable, Hindi-only top-10 recommendation.
     *
     * Form params:
     *   audio            — the conversation audio file (WebM / WAV / MP3 / M4A)
     *   sessionId        — optional
     *   userId           — optional
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ConversationRecommendationResponse> analyzeConversation(
            @RequestPart("audio") MultipartFile audioFile,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "emotionSource", required = false) String emotionSource) {

        double weight = SpeechEmotionFusion.audioWeightFor(emotionSource, audioWeight);
        validateAudio(audioFile);
        sessionId = resolveSessionId(sessionId);
        List<String> warnings = new ArrayList<>();

        log.info("Conversation analysis request: session={}, audioSize={}", sessionId, audioFile.getSize());

        // 0. Decode first: rejects non-audio and silent uploads before speech-to-text,
        //    which would otherwise fail with a 500 or hallucinate a transcript from silence.
        DecodedAudio decoded = decodeOrEmpty(audioFile, warnings);
        List<AcousticFeatureExtractor.Frame> frames = decoded.frames();

        // 1. Segmented Speech-to-Text — real per-segment timestamps decoded from the audio.
        SpeechToTextService.SegmentedTranscript transcript = speechToTextService.transcribeWithSegments(audioFile);
        if (transcript.fullText() == null || transcript.fullText().isBlank()) {
            throw new ConversationProcessingException(
                    "Could not detect any meaningful speech in the uploaded audio. "
                    + "Please check the recording and try again.");
        }

        // 1b. Drop segments with no detectable speech in the audio — speech-to-text hallucinates text
        //     over silence (e.g. "you" over a recording's silent tail), and such a segment would
        //     otherwise become a fake turn and skew speaker clustering.
        List<TranscriptSegmentDTO> speechSegments = dropSegmentsWithoutSpeech(transcript.segments(), frames, warnings);
        String fullText = speechSegments.stream().map(TranscriptSegmentDTO::text).collect(Collectors.joining(" "));

        // 1c. Voice emotion + speaker voiceprint per segment from the local speech-emotion model.
        java.util.Optional<SpeechEmotionClient.Result> voice = decoded.pcm() == null ? java.util.Optional.empty()
                : speechEmotionClient.analyze(decoded.pcm(),
                        speechSegments.stream().map(s -> new long[]{s.startMs(), s.endMs()}).toList(), true);
        if (voice.isEmpty() && decoded.pcm() != null) {
            warnings.add("The voice emotion model (speech-emotion service) was unavailable — each response's emotion "
                    + "comes from the words only, and speakers were separated by pitch only.");
        }

        // 2. Audio-based speaker diarization — voiceprints (timbre, works for same-gender voices) when
        //    available, pitch/energy clustering otherwise — + text-based role labeling, then resolved.
        SpeakerDiarizationService.DiarizationResult pitchDiarization =
                speakerDiarizationService.diarize(frames, speechSegments);
        SpeakerDiarizationService.DiarizationResult voiceprintDiarization = voice
                .map(v -> speakerDiarizationService.diarizeByEmbeddings(speechSegments,
                        v.segments().stream().map(SpeechEmotionClient.SegmentAnalysis::embedding).toList(), frames))
                .orElse(null);
        SpeakerDiarizationService.DiarizationResult diarization =
                SpeakerDiarizationService.choose(pitchDiarization, voiceprintDiarization);
        List<String> textRoles = conversationAnalysisService.labelRoles(speechSegments);
        SpeakerRoleResolver.Resolution resolution = speakerRoleResolver.resolve(diarization, textRoles);
        warnings.addAll(resolution.warnings());

        // Surfaced to the user via the dedicated speakerAssignmentUncertain banner (with its own
        // "Review & Correct" action) rather than a duplicate warnings-array message — resolution's
        // own warning above already explains *why* in diagnostic terms.
        boolean speakerAssignmentUncertain = !diarization.reliable();

        ConversationPipelineService.VoiceEvidence evidence = voice
                .map(v -> new ConversationPipelineService.VoiceEvidence(v.segments(), weight, SER_MODEL))
                .orElse(null);
        ConversationRecommendationResponse response = conversationPipelineService.run(
                fullText, resolution.turns(), frames, resolution.diarizationMethod(),
                speakerAssignmentUncertain, warnings, sessionId, userId, true, evidence);

        return ResponseEntity.ok(response);
    }

    /**
     * Req. #3 — user-driven correction of speaker roles when automatic identification was
     * uncertain. Takes the SAME turns the client was given (with the user's corrected
     * speakerRole values) and recomputes everything downstream (patient text, emotion,
     * variability, Raga selection, Hindi-only recommendations) without needing the audio
     * again — transcription/diarization don't need to rerun, only the role-dependent stages.
     */
    @PostMapping("/reassign-roles")
    public ResponseEntity<ConversationRecommendationResponse> reassignRoles(
            @RequestBody RoleReassignmentRequest request) {

        if (request.getTurns() == null || request.getTurns().isEmpty()) {
            throw new InvalidAudioException("No turns were provided to reassign roles for.");
        }

        List<ConversationTurnDTO> turns = request.getTurns().stream()
                .map(t -> new ConversationTurnDTO(
                        t.getOrder(), normalizeRole(t.getSpeakerRole()), t.getText(), t.getStartMs(), t.getEndMs()))
                .sorted((a, b) -> Integer.compare(a.order(), b.order()))
                .toList();

        String fullTranscript = turns.stream().map(ConversationTurnDTO::text).collect(Collectors.joining(" "));
        String sessionId = resolveSessionId(request.getSessionId());
        List<String> warnings = new ArrayList<>();
        warnings.add("Speaker roles were manually corrected by the user — audio was not re-analyzed, "
                + "so the audio-based arousal profile below reflects the ORIGINAL automatic split, not this correction.");

        // No audio to re-decode here — frames stay empty, so acoustic analysis honestly
        // reports UNKNOWN rather than fabricating a re-analysis that didn't happen.
        ConversationRecommendationResponse response = buildResponseFromTurns(
                fullTranscript, turns, List.of(),
                "Speaker roles manually corrected by the user via /reassign-roles.",
                false, warnings, sessionId, null);

        return ResponseEntity.ok(response);
    }

    // ── Shared pipeline (from turns onward) ──────────────────────────

    private ConversationRecommendationResponse buildResponseFromTurns(
            String fullTranscript,
            List<ConversationTurnDTO> turns,
            List<AcousticFeatureExtractor.Frame> frames,
            String diarizationMethod,
            boolean speakerAssignmentUncertain,
            List<String> warnings,
            String sessionId,
            Long userId) {
        return conversationPipelineService.run(fullTranscript, turns, frames, diarizationMethod,
                speakerAssignmentUncertain, warnings, sessionId, userId, true);
    }

    // ── Acoustic helpers ──────────────────────────────────────────

    /** Decodes the upload to PCM for acoustic analysis; degrades to an empty frame list (no acoustic signal) on failure. */
    private DecodedAudio decodeOrEmpty(MultipartFile audioFile, List<String> warnings) {
        List<AcousticFeatureExtractor.Frame> frames;
        short[] samples;
        try {
            samples = audioTranscodingService.toPcm16Mono16k(audioFile.getBytes(), audioFile.getOriginalFilename());
            frames = acousticFeatureExtractor.analyze(samples);
        } catch (AudioTranscodingService.UndecodableAudioException ex) {
            log.warn("Rejected conversation upload that could not be decoded: {}", ex.getMessage());
            throw new InvalidAudioException("The uploaded file could not be read as audio. Please upload a WAV, MP3, M4A or WebM recording.");
        } catch (Exception ex) {
            log.warn("Audio decoding for acoustic analysis failed — continuing with text-only analysis. Cause: {}", ex.getMessage());
            warnings.add("Audio-based acoustic analysis was unavailable for this recording (the audio decoder, ffmpeg, could not be run) — "
                    + "speaker separation and arousal detection fell back to text-based analysis only.");
            return new DecodedAudio(null, List.of());
        }
        if (!AcousticFeatureExtractor.containsSpeech(frames)) {
            throw new InvalidAudioException("No speech was detected in the recording. Please check the microphone and try again.");
        }
        return new DecodedAudio(samples, frames);
    }

    /**
     * Removes transcript segments whose time range contains no voiced audio, renumbering the rest.
     * Keeps everything when the audio could not be decoded (no frames), since there is nothing to check against.
     */
    private List<TranscriptSegmentDTO> dropSegmentsWithoutSpeech(
            List<TranscriptSegmentDTO> segments, List<AcousticFeatureExtractor.Frame> frames, List<String> warnings) {
        if (frames.isEmpty()) return segments;
        List<TranscriptSegmentDTO> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (TranscriptSegmentDTO s : segments) {
            if (AcousticFeatureExtractor.containsSpeech(frames, s.startMs(), s.endMs())) {
                kept.add(new TranscriptSegmentDTO(kept.size(), s.startMs(), s.endMs(), s.text()));
            } else {
                dropped.add("\"" + s.text().strip() + "\"");
            }
        }
        if (!dropped.isEmpty()) {
            log.info("Dropped {} transcript segment(s) with no detectable speech: {}", dropped.size(), dropped);
            warnings.add("Ignored " + dropped.size() + " transcript segment(s) with no detectable speech in the audio "
                    + "(likely speech-to-text artifacts over silence): " + String.join(", ", dropped) + ".");
        }
        return kept.isEmpty() ? segments : kept;
    }

    // ── Validation ────────────────────────────────────────────────

    private void validateAudio(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new InvalidAudioException("No audio file was received — please upload a conversation recording.");
        }
        if (audioFile.getSize() > MAX_AUDIO_BYTES) {
            throw new InvalidAudioException("Audio file is too large (max 25MB).");
        }
        String contentType = audioFile.getContentType();
        if (contentType != null && ACCEPTED_CONTENT_PREFIXES.stream().noneMatch(contentType::startsWith)) {
            log.warn("Unexpected content type for conversation audio: {}", contentType);
            // Not fatal — browsers/recorders report inconsistent content types — but logged for visibility.
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private String resolveSessionId(String provided) {
        return (provided != null && !provided.isBlank()) ? provided : UUID.randomUUID().toString();
    }

    private String normalizeRole(String raw) {
        String upper = raw == null ? "" : raw.trim().toUpperCase().replace(' ', '_');
        return ConversationTurnDTO.HEALTH_CONSULTANT.equals(upper) ? ConversationTurnDTO.HEALTH_CONSULTANT
                : ConversationTurnDTO.PATIENT;
    }
}
