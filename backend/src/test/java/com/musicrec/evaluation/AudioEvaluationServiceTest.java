package com.musicrec.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.audio.*;
import com.musicrec.service.EmotionAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Audio evaluation: speaker separation and voice / text / fused emotion against a labelled recording. */
class AudioEvaluationServiceTest {

    @TempDir
    Path tmp;

    private final AudioTranscodingService transcoder = mock(AudioTranscodingService.class);
    private final SpeechEmotionClient ser = mock(SpeechEmotionClient.class);
    private final EmotionAnalysisService emotionAnalysis = mock(EmotionAnalysisService.class);
    private final AcousticFeatureExtractor extractor = new AcousticFeatureExtractor();

    private static final String MANIFEST = """
            {"name": "unit", "source": "synthetic-tts", "recordings": [
              {"id": "R1", "file": "r1.mp3", "turns": [
                {"role": "HEALTH_CONSULTANT", "startMs": 0, "endMs": 1000, "text": "How are you?"},
                {"role": "PATIENT", "startMs": 1000, "endMs": 2000, "text": "I feel so sad.", "emotion": "SADNESS"},
                {"role": "HEALTH_CONSULTANT", "startMs": 2000, "endMs": 3000, "text": "Why?"},
                {"role": "PATIENT", "startMs": 3000, "endMs": 4000, "text": "I miss home.", "emotion": "NOSTALGIA"}]}
            ]}""";

    private SpeechEmotionClient.SpeechEmotion voice(String label, double conf) {
        Map<String, Double> p = new LinkedHashMap<>(Map.of("neutral", 0.0, "happy", 0.0, "angry", 0.0, "sad", 0.0));
        p.put(label, conf);
        return new SpeechEmotionClient.SpeechEmotion(label, conf, p);
    }

    private AudioEvaluationService service() throws Exception {
        Files.writeString(tmp.resolve("manifest.json"), MANIFEST);
        Files.write(tmp.resolve("r1.mp3"), new byte[]{1, 2, 3});
        AudioEvaluationService s = new AudioEvaluationService(transcoder, extractor, new SpeakerDiarizationService(extractor),
                ser, emotionAnalysis, new ObjectMapper());
        ReflectionTestUtils.setField(s, "resultsDir", tmp.resolve("results").toString());
        return s;
    }

    private void stubAudio() {
        when(transcoder.toPcm16Mono16k(any(), anyString())).thenReturn(new short[16_000 * 4]);
        double[] a = {1, 0.1, 0}, b = {0, 1, 0.2};
        when(ser.analyze(any(), anyList(), eq(true))).thenReturn(Optional.of(new SpeechEmotionClient.Result(null, List.of(
                new SpeechEmotionClient.SegmentAnalysis(0, 1000, voice("neutral", 0.8), a),
                new SpeechEmotionClient.SegmentAnalysis(1000, 2000, voice("sad", 0.9), b),
                new SpeechEmotionClient.SegmentAnalysis(2000, 3000, voice("neutral", 0.8), a),
                new SpeechEmotionClient.SegmentAnalysis(3000, 4000, voice("happy", 0.85), b)))));
        // Text model: right about NOSTALGIA but unsure and wrong on the first response.
        when(emotionAnalysis.analyzeBatch(anyList())).thenReturn(List.of(
                new EmotionAnalysisService.SegmentEmotion(0, "CALM", 0.5, 0.4),
                new EmotionAnalysisService.SegmentEmotion(1, "NOSTALGIA", 0.6, 0.9)));
    }

    @Test
    void scoresSpeakerSeparationAndEachEmotionSource() throws Exception {
        stubAudio();
        AudioEvaluationReport r = service().run(tmp.resolve("manifest.json").toString());

        assertThat(r.recordingsCompleted()).isEqualTo(1);
        assertThat(r.voiceprintRoleAccuracy()).isEqualTo(1.0);
        assertThat(r.voiceprintReliableRate()).isEqualTo(1.0);
        // Voice-only: SADNESS→sad ✓; NOSTALGIA has no voice class, so it is excluded.
        assertThat(r.voiceOnlyAccuracy()).isEqualTo(1.0);
        assertThat(r.voiceScoredResponses()).isEqualTo(1);
        // Text-only: CALM ✗, NOSTALGIA ✓.
        assertThat(r.textOnlyAccuracy()).isEqualTo(0.5);
        // Fused at 0.5: the confident sad voice fixes the unsure CALM; confident NOSTALGIA text is kept.
        assertThat(r.fusedAccuracyByWeight()).containsEntry("0.5", 1.0).containsEntry("0.0", 0.5);
        assertThat(r.source()).isEqualTo("synthetic-tts");
        assertThat(r.notes()).anyMatch(n -> n.contains("synthetic"));
        try (var files = Files.list(tmp.resolve("results"))) {
            assertThat(files.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("audio-eval-") && n.endsWith(".md"));
        }
    }

    @Test
    void withoutTheSpeechEmotionServiceTheRunFailsClearly() throws Exception {
        when(transcoder.toPcm16Mono16k(any(), anyString())).thenReturn(new short[16_000 * 4]);
        when(ser.analyze(any(), anyList(), anyBoolean())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().run(tmp.resolve("manifest.json").toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("speech-emotion service");
    }

    @Test
    void aMissingAudioFileFailsThatRecordingOnly() throws Exception {
        stubAudio();
        AudioEvaluationService s = service();
        Files.delete(tmp.resolve("r1.mp3"));

        AudioEvaluationReport r = s.run(tmp.resolve("manifest.json").toString());

        assertThat(r.recordingsCompleted()).isZero();
        assertThat(r.recordings().get(0).error()).contains("r1.mp3");
    }
}
