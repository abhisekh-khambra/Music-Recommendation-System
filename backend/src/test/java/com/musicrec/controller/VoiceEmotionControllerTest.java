package com.musicrec.controller;

import com.musicrec.audio.*;
import com.musicrec.dto.ConversationRecommendationResponse;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.TranscriptSegmentDTO;
import com.musicrec.repository.ChatHistoryRepository;
import com.musicrec.repository.TrackRepository;
import com.musicrec.repository.UserRepository;
import com.musicrec.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Meeting requirement: "when we are giving audio input… we need to process the voice or audio signal
 * to detect emotions" — both audio modes use the speech-emotion model, with a selectable emotion source.
 */
@SuppressWarnings("unchecked") // ArgumentCaptor.forClass(List.class) cannot carry the generic type
class VoiceEmotionControllerTest {

    private static final MockMultipartFile UPLOAD =
            new MockMultipartFile("audio", "clip.mp3", "audio/mpeg", new byte[]{1, 2, 3, 4});

    private final SpeechToTextService stt = mock(SpeechToTextService.class);
    private final AudioTranscodingService transcoder = mock(AudioTranscodingService.class);
    private final AcousticFeatureExtractor extractor = new AcousticFeatureExtractor();
    private final SpeechEmotionClient ser = mock(SpeechEmotionClient.class);

    /** 4 s of a voiced 150 Hz tone — enough for the speech check and pitch analysis. */
    private short[] tone() {
        short[] s = new short[16_000 * 4];
        for (int i = 0; i < s.length; i++) s[i] = (short) (12000 * Math.sin(2 * Math.PI * 150 * i / 16_000.0));
        return s;
    }

    private SpeechEmotionClient.SpeechEmotion voice(String label, double conf) {
        Map<String, Double> p = new LinkedHashMap<>(Map.of("neutral", 0.0, "happy", 0.0, "angry", 0.0, "sad", 0.0));
        p.put(label, conf);
        return new SpeechEmotionClient.SpeechEmotion(label, conf, p);
    }

    // ── Conversation mode ──────────────────────────────────────────

    private final ConversationAnalysisService conversationAnalysis = mock(ConversationAnalysisService.class);
    private final ConversationPipelineService pipeline = mock(ConversationPipelineService.class);

    private ConversationController conversationController() {
        ConversationController c = new ConversationController(stt, conversationAnalysis, new SpeakerRoleResolver(),
                transcoder, extractor, new SpeakerDiarizationService(extractor), pipeline, ser);
        ReflectionTestUtils.setField(c, "audioWeight", 0.3);
        return c;
    }

    private void conversationInput() {
        when(transcoder.toPcm16Mono16k(any(), anyString())).thenReturn(tone());
        List<TranscriptSegmentDTO> segs = List.of(
                new TranscriptSegmentDTO(0, 0, 1000, "How are you?"), new TranscriptSegmentDTO(1, 1000, 2000, "I'm fine."),
                new TranscriptSegmentDTO(2, 2000, 3000, "Sleep?"), new TranscriptSegmentDTO(3, 3000, 4000, "Not well."));
        when(stt.transcribeWithSegments(any())).thenReturn(new SpeechToTextService.SegmentedTranscript("...", segs));
        when(conversationAnalysis.labelRoles(anyList())).thenReturn(
                List.of("HEALTH_CONSULTANT", "PATIENT", "HEALTH_CONSULTANT", "PATIENT"));
        when(pipeline.run(anyString(), anyList(), anyList(), anyString(), anyBoolean(), anyList(), anyString(),
                any(), anyBoolean(), any())).thenReturn(ConversationRecommendationResponse.builder().build());
    }

    private SpeechEmotionClient.Result twoVoices() {
        double[] a = {1, 0.1, 0}, b = {0, 1, 0.2};
        return new SpeechEmotionClient.Result(null, List.of(
                new SpeechEmotionClient.SegmentAnalysis(0, 1000, voice("neutral", 0.8), a),
                new SpeechEmotionClient.SegmentAnalysis(1000, 2000, voice("sad", 0.9), b),
                new SpeechEmotionClient.SegmentAnalysis(2000, 3000, voice("neutral", 0.7), a),
                new SpeechEmotionClient.SegmentAnalysis(3000, 4000, voice("sad", 0.8), b)));
    }

    private ArgumentCaptor<ConversationPipelineService.VoiceEvidence> captureEvidence(ArgumentCaptor<String> method,
                                                                                    ArgumentCaptor<List<String>> warnings) {
        ArgumentCaptor<ConversationPipelineService.VoiceEvidence> ev = ArgumentCaptor.forClass(ConversationPipelineService.VoiceEvidence.class);
        verify(pipeline).run(anyString(), anyList(), anyList(), method.capture(), anyBoolean(), warnings.capture(),
                anyString(), any(), anyBoolean(), ev.capture());
        return ev;
    }

    @Test
    void conversationUsesVoiceEmotionAndVoiceprintsAtTheConfiguredWeight() {
        conversationInput();
        when(ser.analyze(any(), anyList(), eq(true))).thenReturn(Optional.of(twoVoices()));

        conversationController().analyzeConversation(UPLOAD, null, null, null);

        ArgumentCaptor<String> method = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> warnings = ArgumentCaptor.forClass(List.class);
        var ev = captureEvidence(method, warnings).getValue();
        assertThat(ev).isNotNull();
        assertThat(ev.audioWeight()).isEqualTo(0.3);
        assertThat(ev.segments()).hasSize(4);
        assertThat(method.getValue()).containsIgnoringCase("voiceprint");
    }

    @Test
    void emotionSourceVoiceTakesTheEmotionFromTheVoice() {
        conversationInput();
        when(ser.analyze(any(), anyList(), eq(true))).thenReturn(Optional.of(twoVoices()));

        conversationController().analyzeConversation(UPLOAD, null, null, "VOICE");

        ArgumentCaptor<String> method = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> warnings = ArgumentCaptor.forClass(List.class);
        assertThat(captureEvidence(method, warnings).getValue().audioWeight()).isEqualTo(1.0);
    }

    @Test
    void withoutTheSpeechEmotionServiceTheConversationIsTextOnlyAndSaysSo() {
        conversationInput();
        when(ser.analyze(any(), anyList(), anyBoolean())).thenReturn(Optional.empty());

        conversationController().analyzeConversation(UPLOAD, null, null, null);

        ArgumentCaptor<String> method = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> warnings = ArgumentCaptor.forClass(List.class);
        assertThat(captureEvidence(method, warnings).getValue()).isNull();
        assertThat(warnings.getValue()).anyMatch(w -> w.contains("voice emotion"));
    }

    @Test
    void unknownEmotionSourceIsRejected() {
        assertThatThrownBy(() -> conversationController().analyzeConversation(UPLOAD, null, null, "MAGIC"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("emotionSource");
    }

    // ── Voice mode ─────────────────────────────────────────────────

    private final EmotionAnalysisService emotionAnalysis = mock(EmotionAnalysisService.class);
    private final MusicRecommendationService recommendation = mock(MusicRecommendationService.class);

    private RecommendationController voiceController() {
        RecommendationController c = new RecommendationController(emotionAnalysis, recommendation, stt,
                mock(ChatHistoryRepository.class), mock(UserRepository.class), mock(TrackRepository.class),
                transcoder, extractor, new AcousticEmotionAnalysisService(), new MultimodalEmotionFusionService(), ser);
        ReflectionTestUtils.setField(c, "audioWeight", 0.3);
        ReflectionTestUtils.setField(c, "voiceTextConfidence", 0.6);
        return c;
    }

    private void voiceInput() {
        when(transcoder.toPcm16Mono16k(any(), anyString())).thenReturn(tone());
        when(stt.transcribe(any())).thenReturn("I am okay I guess");
        EmotionMetadata text = new EmotionMetadata();
        text.setPrimaryEmotion("CALM");
        text.setIntensity(0.4);
        text.setLanguage("English");
        text.setSecondaryEmotions(new ArrayList<>());
        when(emotionAnalysis.analyze(anyString())).thenReturn(text);
        when(recommendation.recommend(any(EmotionMetadata.class)))
                .thenReturn(new MusicRecommendationService.RankingResult(List.of(), "m"));
        when(ser.analyze(any(), eq(List.of()), eq(false)))
                .thenReturn(Optional.of(new SpeechEmotionClient.Result(voice("sad", 0.9), List.of())));
    }

    private EmotionMetadata recommendedEmotion() {
        ArgumentCaptor<EmotionMetadata> e = ArgumentCaptor.forClass(EmotionMetadata.class);
        verify(recommendation).recommend(e.capture());
        return e.getValue();
    }

    @Test
    void voiceModeVoiceSourceTakesTheEmotionFromTheVoice() {
        voiceInput();
        voiceController().recommendByVoice(UPLOAD, null, null, "VOICE");

        EmotionMetadata e = recommendedEmotion();
        assertThat(e.getPrimaryEmotion()).isEqualTo("SADNESS");
        assertThat(e.getTextEmotion()).isEqualTo("CALM");
        assertThat(e.getSpeechEmotion().label()).isEqualTo("sad");
        assertThat(e.getEmotionSource()).isEqualTo("text+audio");
        assertThat(e.getSecondaryEmotions()).contains("CALM");
    }

    @Test
    void voiceModeFusedKeepsMoreConfidentWordsButReportsTheVoice() {
        voiceInput();
        voiceController().recommendByVoice(UPLOAD, null, null, null);

        EmotionMetadata e = recommendedEmotion();
        assertThat(e.getPrimaryEmotion()).isEqualTo("CALM");
        assertThat(e.getSpeechEmotion().label()).isEqualTo("sad");
        assertThat(e.getFusionNote()).contains("voice");
    }

    @Test
    void voiceModeTextSourceIgnoresTheVoice() {
        voiceInput();
        voiceController().recommendByVoice(UPLOAD, null, null, "TEXT");
        assertThat(recommendedEmotion().getPrimaryEmotion()).isEqualTo("CALM");
    }
}
