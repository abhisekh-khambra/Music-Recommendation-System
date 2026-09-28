package com.musicrec.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.ConversationAnalysisDTO;
import com.musicrec.dto.EmotionMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
}
