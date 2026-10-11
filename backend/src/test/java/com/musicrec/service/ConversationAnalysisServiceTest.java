package com.musicrec.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.ConversationAnalysisDTO;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.EmotionalVariabilityDTO;
import com.musicrec.dto.PatientSegmentEmotionDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;

class ConversationAnalysisServiceTest {

    private ConversationAnalysisService newService() {
        // buildAnalysis() never touches the chat client — it's pure code over an
        // already-computed EmotionMetadata — so an unstubbed mock is sufficient here.
        return new ConversationAnalysisService(mock(ChatClient.class), new ObjectMapper());
    }

    @Test
    void buildAnalysisNeverProducesDiagnosticLanguage() {
        EmotionMetadata emotion = new EmotionMetadata();
        emotion.setPrimaryEmotion("STRESS");
        emotion.setSecondaryEmotions(List.of("ANXIETY"));
        emotion.setIntensity(0.82);
        emotion.setLanguage("English");
        emotion.setMoodKeywords(List.of("overwhelmed", "tense"));

        ConversationAnalysisDTO analysis = newService().buildAnalysis(emotion);

        assertThat(analysis.getContextSummary())
                .contains("Conversation indicates")
                .contains("elevated")
                .contains("stress-related emotional cues")
                .doesNotContainIgnoringCase("diagnos")
                .doesNotContainIgnoringCase("disorder")
                .doesNotContainIgnoringCase("patient has");

        assertThat(analysis.isStressCuesDetected()).isTrue();
        assertThat(analysis.isAnxietyCuesDetected()).isTrue();
        assertThat(analysis.isSadnessCuesDetected()).isFalse();
        assertThat(analysis.getSentiment()).isEqualTo("NEGATIVE");
        assertThat(analysis.getDisclaimer()).containsIgnoringCase("not a medical or clinical diagnosis");
    }

    @Test
    void mixedPositiveAndNegativeStatesYieldMixedSentiment() {
        EmotionMetadata emotion = new EmotionMetadata();
        emotion.setPrimaryEmotion("JOY");
        emotion.setSecondaryEmotions(List.of("SADNESS"));
        emotion.setIntensity(0.5);
        emotion.setLanguage("English");
        emotion.setMoodKeywords(List.of());

        ConversationAnalysisDTO analysis = newService().buildAnalysis(emotion);

        assertThat(analysis.getSentiment()).isEqualTo("MIXED");
    }

    private PatientSegmentEmotionDTO segmentOf(int order, String emotion, double intensity) {
        return PatientSegmentEmotionDTO.builder()
                .order(order).startMs(order * 1000L).endMs((order + 1) * 1000L)
                .text("segment " + order).primaryEmotion(emotion).intensity(intensity).confidence(0.8)
                .build();
    }

    @Test
    void variabilityReportsDominantEmotionAndPercentageDistribution() {
        List<PatientSegmentEmotionDTO> segments = List.of(
                segmentOf(0, "ANXIETY", 0.8),
                segmentOf(1, "ANXIETY", 0.7),
                segmentOf(2, "SADNESS", 0.9),
                segmentOf(3, "NOSTALGIA", 0.6));

        EmotionalVariabilityDTO v = newService().buildVariability(segments);

        assertThat(v.getDominantEmotion()).isEqualTo("ANXIETY");
        assertThat(v.getEmotionDistribution())
                .containsExactly(entry("ANXIETY", 50.0), entry("SADNESS", 25.0), entry("NOSTALGIA", 25.0));
        assertThat(v.getEmotionDistribution().values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(100.0);
    }

    @Test
    void dominantEmotionTieIsBrokenByTotalIntensity() {
        List<PatientSegmentEmotionDTO> segments = List.of(
                segmentOf(0, "ANXIETY", 0.4),
                segmentOf(1, "SADNESS", 0.9));

        EmotionalVariabilityDTO v = newService().buildVariability(segments);

        assertThat(v.getDominantEmotion()).isEqualTo("SADNESS");
        assertThat(v.getEmotionDistribution()).containsExactly(entry("SADNESS", 50.0), entry("ANXIETY", 50.0));
    }

    @Test
    void distributionPercentagesAreRoundedButStillSumTo100() {
        List<PatientSegmentEmotionDTO> segments = List.of(
                segmentOf(0, "JOY", 0.5), segmentOf(1, "CALM", 0.5), segmentOf(2, "LOVE", 0.5));

        EmotionalVariabilityDTO v = newService().buildVariability(segments);

        assertThat(v.getEmotionDistribution().values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void variabilityCountsDistinctEmotionsAndTransitionsAcrossSegments() {
        List<PatientSegmentEmotionDTO> segments = List.of(
                segmentOf(0, "STRESS", 0.8),
                segmentOf(1, "STRESS", 0.7),
                segmentOf(2, "RELAXATION", 0.3),
                segmentOf(3, "JOY", 0.6));

        EmotionalVariabilityDTO variability = newService().buildVariability(segments);

        assertThat(variability.getDistinctEmotionCount()).isEqualTo(3); // STRESS, RELAXATION, JOY
        assertThat(variability.getTransitionCount()).isEqualTo(2);      // STRESS->RELAXATION, RELAXATION->JOY
        assertThat(variability.getSegments()).hasSize(4);
    }

    @Test
    void singleFixedEmotionAcrossAllSegmentsHasNoTransitions() {
        List<PatientSegmentEmotionDTO> segments = List.of(
                segmentOf(0, "SADNESS", 0.6),
                segmentOf(1, "SADNESS", 0.65));

        EmotionalVariabilityDTO variability = newService().buildVariability(segments);

        assertThat(variability.getDistinctEmotionCount()).isEqualTo(1);
        assertThat(variability.getTransitionCount()).isZero();
    }

    @Test
    void emptySegmentListReturnsZeroedVariabilityRatherThanThrowing() {
        EmotionalVariabilityDTO variability = newService().buildVariability(List.of());

        assertThat(variability.getSegments()).isEmpty();
        assertThat(variability.getDominantEmotion()).isNull();
        assertThat(variability.getEmotionDistribution()).isEmpty();
        assertThat(variability.getDistinctEmotionCount()).isZero();
        assertThat(variability.getTransitionCount()).isZero();
    }

    @Test
    void retrievalEmotionIncludesTheDistributionsOtherEmotionsInShareOrder() {
        EmotionMetadata overall = new EmotionMetadata();
        overall.setPrimaryEmotion("SADNESS");
        overall.setSecondaryEmotions(List.of("MELANCHOLY"));
        EmotionalVariabilityDTO v = newService().buildVariability(List.of(
                segmentOf(0, "ANXIETY", 0.8), segmentOf(1, "ANXIETY", 0.7),
                segmentOf(2, "SADNESS", 0.9), segmentOf(3, "NOSTALGIA", 0.6)));

        EmotionMetadata forRetrieval = newService().withDistributionEmotions(overall, v);

        assertThat(forRetrieval.getPrimaryEmotion()).isEqualTo("SADNESS");
        assertThat(forRetrieval.getSecondaryEmotions()).containsExactly("ANXIETY", "NOSTALGIA", "MELANCHOLY");
        assertThat(overall.getSecondaryEmotions()).containsExactly("MELANCHOLY"); // original not mutated
    }

    @Test
    void distributionIsOrderedSoTheDominantEmotionNeverShowsASmallerShare() {
        // Seen live: 7 emotions once each gave ANXIETY 14.2% (dominant) while every other emotion showed 14.3%
        List<PatientSegmentEmotionDTO> segments = new java.util.ArrayList<>();
        String[] emotions = {"ANXIETY", "SADNESS", "ANGER", "STRESS", "NOSTALGIA", "MELANCHOLY", "CALM"};
        for (int i = 0; i < emotions.length; i++) segments.add(segmentOf(i, emotions[i], 0.9 - i * 0.05));

        EmotionalVariabilityDTO v = newService().buildVariability(segments);

        List<Double> shares = new java.util.ArrayList<>(v.getEmotionDistribution().values());
        for (int i = 1; i < shares.size(); i++) assertThat(shares.get(i - 1)).isGreaterThanOrEqualTo(shares.get(i));
        assertThat(v.getEmotionDistribution().get(v.getDominantEmotion())).isEqualTo(java.util.Collections.max(shares));
        assertThat(shares.stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.001));
    }
}
