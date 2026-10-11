package com.musicrec.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.EmotionMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The keyword fallback runs whenever the LLM call fails (e.g. Groq rate limit). A mocked
 * ChatClient returns null from prompt(), which forces that path.
 */
class EmotionAnalysisServiceFallbackTest {

    private final EmotionAnalysisService service =
            new EmotionAnalysisService(mock(ChatClient.class), new ObjectMapper());

    @Test
    void fallbackDetectsNostalgiaInEnglish() {
        EmotionMetadata m = service.analyze("I felt warm and nostalgic looking at old photos, I really miss those days.");
        assertThat(m.getPrimaryEmotion()).isEqualTo("NOSTALGIA");
    }

    @Test
    void fallbackDetectsNostalgiaInHinglish() {
        EmotionMetadata m = service.analyze("Purane dinon ki bahut yaad aati hai");
        assertThat(m.getPrimaryEmotion()).isEqualTo("NOSTALGIA");
    }

    @Test
    void fallbackDetectsAnxietySeparatelyFromStress() {
        EmotionMetadata m = service.analyze("I am so anxious and nervous about tomorrow");
        assertThat(m.getPrimaryEmotion()).isEqualTo("ANXIETY");
    }

    @Test
    void fallbackDetectsFuriousAsAnger() {
        assertThat(service.analyze("My colleague took credit for my work again. I am absolutely furious.").getPrimaryEmotion())
                .isEqualTo("ANGER");
        assertThat(service.analyze("Main bahut naraz hoon").getPrimaryEmotion()).isEqualTo("ANGER");
    }

    @Test
    void fallbackStillDetectsExistingEmotions() {
        assertThat(service.analyze("I am so happy today").getPrimaryEmotion()).isEqualTo("JOY");
        assertThat(service.analyze("bahut tension hai").getPrimaryEmotion()).isEqualTo("STRESS");
        assertThat(service.analyze("I feel sad").getPrimaryEmotion()).isEqualTo("SADNESS");
    }

    @Test
    void fallbackResultIsMarkedAsFallback() {
        assertThat(service.analyze("I miss my childhood").getContextSummary()).containsIgnoringCase("fallback");
    }
}
