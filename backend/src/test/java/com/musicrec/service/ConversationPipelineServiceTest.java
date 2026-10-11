package com.musicrec.service;

import com.musicrec.audio.AcousticEmotionAnalysisService;
import com.musicrec.audio.AcousticFeatureExtractor;
import com.musicrec.dto.ConversationRecommendationResponse;
import com.musicrec.dto.ConversationTurnDTO;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.EmotionSlotDTO;
import com.musicrec.dto.EmotionalVariabilityDTO;
import com.musicrec.dto.RagaSelectionDTO;
import com.musicrec.dto.TrackDTO;
import com.musicrec.repository.ChatHistoryRepository;
import com.musicrec.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationPipelineServiceTest {

    private final EmotionAnalysisService emotionAnalysis = mock(EmotionAnalysisService.class);
    private final ConversationAnalysisService conversationAnalysis = mock(ConversationAnalysisService.class);
    private final RagaSelectionService ragaSelection = mock(RagaSelectionService.class);
    private final MusicRecommendationService recommendation = mock(MusicRecommendationService.class);
    private final ChatHistoryRepository history = mock(ChatHistoryRepository.class);

    private ConversationPipelineService pipeline() {
        EmotionMetadata emotion = new EmotionMetadata();
        emotion.setPrimaryEmotion("SADNESS");
        emotion.setIntensity(0.6);
        emotion.setLanguage("English");
        when(emotionAnalysis.analyze(anyString())).thenReturn(emotion);
        when(emotionAnalysis.analyzeBatch(anyList())).thenReturn(List.of());
        Map<String, Double> dist = new LinkedHashMap<>(Map.of("SADNESS", 100.0));
        when(conversationAnalysis.buildVariability(anyList())).thenReturn(
                EmotionalVariabilityDTO.builder().segments(List.of()).emotionDistribution(dist).build());
        when(conversationAnalysis.withDistributionEmotions(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(ragaSelection.selectRaga(any(EmotionMetadata.class), any(), eq(true)))
                .thenReturn(RagaSelectionDTO.builder().raga("Darbari Kanada").warnings(List.of()).build());
        when(ragaSelection.ragasPerEmotion(any(), eq(true))).thenReturn(new RagaSelectionService.RagaPerEmotion(
                new LinkedHashMap<>(Map.of("SADNESS", "Darbari Kanada")), List.of("JOY has no raga with Hindi tracks")));

        ConversationPipelineService p = new ConversationPipelineService(conversationAnalysis, emotionAnalysis,
                ragaSelection, recommendation, mock(AcousticFeatureExtractor.class),
                mock(AcousticEmotionAnalysisService.class), history, mock(UserRepository.class));
        ReflectionTestUtils.setField(p, "topNResults", 2);
        return p;
    }

    private List<TrackDTO> twoTracks() {
        TrackDTO a = new TrackDTO(); a.setId(1L); a.setLanguage("Hindi");
        TrackDTO b = new TrackDTO(); b.setId(2L); b.setLanguage("Hindi");
        return List.of(a, b);
    }

    private ConversationRecommendationResponse run(ConversationPipelineService p, boolean persist) {
        List<ConversationTurnDTO> turns = List.of(ConversationTurnDTO.withoutTimestamps(0, ConversationTurnDTO.PATIENT, "I feel low"));
        return p.run("I feel low", turns, List.of(), "test", false, new ArrayList<>(), "s", null, persist);
    }

    @Test
    void songSelectionFallbackIsFlaggedAndWarned() {
        ConversationPipelineService p = pipeline();
        when(recommendation.recommend(any(), anyString(), eq(true), any()))
                .thenReturn(new MusicRecommendationService.RankingResult(twoTracks(), "m", List.of(), false));

        ConversationRecommendationResponse r = run(p, false);

        assertThat(r.isLlmSongSelection()).isFalse();
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("song selection"));
    }

    @Test
    void llmSongSelectionHasNoFallbackWarning() {
        ConversationPipelineService p = pipeline();
        when(recommendation.recommend(any(), anyString(), eq(true), any()))
                .thenReturn(new MusicRecommendationService.RankingResult(twoTracks(), "m", List.of(), true));

        ConversationRecommendationResponse r = run(p, false);

        assertThat(r.isLlmSongSelection()).isTrue();
        assertThat(r.getWarnings()).noneMatch(w -> w.contains("song selection"));
    }

    @Test
    void unfilledEmotionShareIsWarned() {
        ConversationPipelineService p = pipeline();
        when(recommendation.recommend(any(), anyString(), eq(true), any()))
                .thenReturn(new MusicRecommendationService.RankingResult(twoTracks(), "m",
                        List.of(new EmotionSlotDTO("STRESS", 100.0, 2, 1)), true));

        ConversationRecommendationResponse r = run(p, false);

        assertThat(r.getEmotionAllocation()).hasSize(1);
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("STRESS") && w.contains("only 1"));
    }

    @Test
    void eachEmotionShareGetsItsOwnRagaAndMissingRagasAreWarned() {
        ConversationPipelineService p = pipeline();
        when(recommendation.recommend(any(), anyString(), eq(true), any()))
                .thenReturn(new MusicRecommendationService.RankingResult(twoTracks(), "m"));

        ConversationRecommendationResponse r = run(p, false);

        org.mockito.ArgumentCaptor<MusicRecommendationService.PlaylistPlan> plan =
                org.mockito.ArgumentCaptor.forClass(MusicRecommendationService.PlaylistPlan.class);
        verify(recommendation).recommend(any(), anyString(), eq(true), plan.capture());
        assertThat(plan.getValue().distribution()).containsEntry("SADNESS", 100.0);
        assertThat(plan.getValue().ragaByEmotion()).containsEntry("SADNESS", "Darbari Kanada");
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("JOY has no raga"));
    }

    private ConversationRecommendationResponse runWithVoice(ConversationPipelineService p, String voiceLabel,
                                                            double voiceConf, double audioWeight) {
        when(emotionAnalysis.analyzeBatch(anyList())).thenReturn(List.of(
                new EmotionAnalysisService.SegmentEmotion(0, "CALM", 0.5, 0.4)));
        when(recommendation.recommend(any(), anyString(), eq(true), any()))
                .thenReturn(new MusicRecommendationService.RankingResult(twoTracks(), "m"));
        Map<String, Double> probs = new LinkedHashMap<>(Map.of("neutral", 0.0, "happy", 0.0, "angry", 0.0, "sad", 0.0));
        probs.put(voiceLabel, voiceConf);
        var voice = new com.musicrec.audio.SpeechEmotionClient.SegmentAnalysis(0, 3000,
                new com.musicrec.audio.SpeechEmotionClient.SpeechEmotion(voiceLabel, voiceConf, probs), null);
        List<ConversationTurnDTO> turns = List.of(new ConversationTurnDTO(0, ConversationTurnDTO.PATIENT, "I'm fine", 0, 3000));
        return p.run("I'm fine", turns, List.of(), "test", false, new ArrayList<>(), "s", null, false,
                new ConversationPipelineService.VoiceEvidence(List.of(voice), audioWeight, "test voice model"));
    }

    @Test
    void aClearVoiceDecidesAPatientResponseWhoseWordsAreAmbiguous() {
        ConversationRecommendationResponse r = runWithVoice(pipeline(), "sad", 0.95, 0.5);

        var seg = r.getEmotionalVariability();
        org.mockito.ArgumentCaptor<List<com.musicrec.dto.PatientSegmentEmotionDTO>> segs =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(conversationAnalysis).buildVariability(segs.capture());
        var s = segs.getValue().get(0);
        assertThat(s.getTextEmotion()).isEqualTo("CALM");
        assertThat(s.getVoiceEmotion()).isEqualTo("sad");
        assertThat(s.getVoiceEmotionConfidence()).isEqualTo(0.95);
        assertThat(s.getPrimaryEmotion()).isEqualTo("SADNESS");
        assertThat(s.getEmotionSource()).isEqualTo("text+audio");
        assertThat(r.getSpeechEmotionMethod()).contains("test voice model");
        assertThat(seg).isNotNull();
    }

    @Test
    void textOnlyWeightIgnoresTheVoiceButStillReportsIt() {
        runWithVoice(pipeline(), "sad", 0.95, 0.0);

        org.mockito.ArgumentCaptor<List<com.musicrec.dto.PatientSegmentEmotionDTO>> segs =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(conversationAnalysis).buildVariability(segs.capture());
        assertThat(segs.getValue().get(0).getPrimaryEmotion()).isEqualTo("CALM");
        assertThat(segs.getValue().get(0).getVoiceEmotion()).isEqualTo("sad");
    }

    @Test
    void withoutVoiceEvidenceSegmentsAreTextOnly() {
        ConversationPipelineService p = pipeline();
        when(emotionAnalysis.analyzeBatch(anyList())).thenReturn(List.of(
                new EmotionAnalysisService.SegmentEmotion(0, "JOY", 0.5, 0.9)));
        when(recommendation.recommend(any(), anyString(), eq(true), any()))
                .thenReturn(new MusicRecommendationService.RankingResult(twoTracks(), "m"));

        ConversationRecommendationResponse r = run(p, false);

        org.mockito.ArgumentCaptor<List<com.musicrec.dto.PatientSegmentEmotionDTO>> segs =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(conversationAnalysis).buildVariability(segs.capture());
        assertThat(segs.getValue().get(0).getEmotionSource()).isEqualTo("text");
        assertThat(segs.getValue().get(0).getVoiceEmotion()).isNull();
        assertThat(r.getSpeechEmotionMethod()).contains("not available");
    }

    @Test
    void evaluationRunsDoNotWriteChatHistory() {
        ConversationPipelineService p = pipeline();
        when(recommendation.recommend(any(), anyString(), eq(true), any()))
                .thenReturn(new MusicRecommendationService.RankingResult(twoTracks(), "m"));

        run(p, false);
        verify(history, never()).save(any());

        run(p, true);
        verify(history).save(any());
    }
}
