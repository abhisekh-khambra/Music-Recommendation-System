package com.musicrec.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.EmotionMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The LLM occasionally returns malformed JSON (seen live: "intensity": 0. nine) — one bad reply must not discard the analysis. */
class EmotionAnalysisServiceRetryTest {

    private static final String MALFORMED = """
            {"primary_emotion": "SADNESS", "secondary_emotions": ["MELANCHOLY"], "intensity": 0. nine, "language": "English"}
            """;
    private static final String VALID = """
            {"primary_emotion": "SADNESS", "secondary_emotions": ["MELANCHOLY"], "intensity": 0.9, "language": "English",
             "context_summary": "The user is grieving."}
            """;

    @Test
    void retriesOnceWhenTheLlmReturnsMalformedJson() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        String unrepairable = "{\"primary_emotion\": \"SADNESS\", \"intensity\": very high}";
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(unrepairable, VALID);

        EmotionMetadata m = new EmotionAnalysisService(chatClient, new ObjectMapper())
                .analyze("My grandmother passed away last week. I feel empty.");

        assertThat(m.getPrimaryEmotion()).isEqualTo("SADNESS");
        assertThat(m.getIntensity()).isEqualTo(0.9);
        assertThat(m.getContextSummary()).doesNotContainIgnoringCase("fallback");
    }

    @Test
    void repairsSpelledOutDecimalDigitWithoutNeedingARetry() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(MALFORMED, MALFORMED);

        EmotionMetadata m = new EmotionAnalysisService(chatClient, new ObjectMapper()).analyze("My grandmother passed away");

        assertThat(m.getPrimaryEmotion()).isEqualTo("SADNESS");
        assertThat(m.getIntensity()).isEqualTo(0.9);
    }

    @Test
    void fallsBackToKeywordsWhenTheRetryIsAlsoMalformed() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        String unrepairable = "{\"primary_emotion\": \"SADNESS\", \"intensity\": very high}";
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(unrepairable, unrepairable);

        EmotionMetadata m = new EmotionAnalysisService(chatClient, new ObjectMapper()).analyze("I feel sad");

        assertThat(m.getPrimaryEmotion()).isEqualTo("SADNESS");
        assertThat(m.getContextSummary()).containsIgnoringCase("fallback");
    }
}
