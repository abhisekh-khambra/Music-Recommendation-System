package com.musicrec.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.EmotionMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Sends user text to Groq LLM via Spring AI and extracts a structured
 * EmotionMetadata object (primary emotion, intensity, language, etc.).
 *
 * Pipeline position: Step 1 of Retrieve-Fuse-Rerank.
 */
@Service
@Slf4j
public class EmotionAnalysisService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String EMOTION_USER_TEMPLATE = """
            Analyze the following user message and return ONLY a JSON object with this exact schema:
            {
              "primary_emotion": "<one of: JOY, SADNESS, STRESS, ANGER, RELAXATION, ANXIETY, EXCITEMENT, MELANCHOLY, NOSTALGIA, LOVE, FRUSTRATION, CALM>",
              "secondary_emotions": ["<emotion1>", "<emotion2>"],
              "intensity": <0.0 to 1.0>,
              "language": "<English or Hindi>",
              "mood_keywords": ["<keyword1>", "<keyword2>", "<keyword3>"],
              "context_summary": "<one short sentence summarizing the emotional context>",
              "expanded_emotions": ["<3-5 semantically related emotions from the same list above>"],
              "expanded_mood_tags": ["<5-8 lowercase mood descriptor words useful for music retrieval>"]
            }

            User message: "%s"

            Rules:
            - Detect the language (Hindi or English) from the text itself.
            - intensity 0.0 = barely perceptible, 1.0 = overwhelming.
            - Choose primary_emotion that BEST represents the dominant feeling.
            - secondary_emotions: 1-3 co-occurring emotions.
            - mood_keywords: 3-5 words/phrases describing the mood.
            - expanded_emotions: semantically adjacent emotions that could also match this state.
              Example: STRESS → ["ANXIETY", "FRUSTRATION", "MELANCHOLY", "CALM"]
              (include calming opposites — music therapy often uses contrast to soothe)
            - expanded_mood_tags: lowercase single words useful as music search tags.
              Example: STRESS → ["calming", "peaceful", "focus", "meditation", "breathing", "soothing"]
            - Return ONLY the JSON. No markdown, no explanation.
            """;

    public EmotionAnalysisService(
            @Qualifier("emotionChatClient") ChatClient chatClient,
            ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Analyzes emotion from free-form text (Hindi or English).
     *
     * @param userText raw text from the user
     * @return structured EmotionMetadata, or a safe fallback on LLM/parse failure
     */
    public EmotionMetadata analyze(String userText) {
        log.debug("Analyzing emotion for text: [{}]", userText);

        String prompt = EMOTION_USER_TEMPLATE.formatted(escapeJson(userText));

        try {
            String rawJson = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            log.debug("Groq emotion raw response: {}", rawJson);

            String cleanedJson = extractJson(rawJson);
            EmotionMetadata metadata = objectMapper.readValue(cleanedJson, EmotionMetadata.class);
            log.info("Detected emotion: {} (intensity={}) [{}]",
                    metadata.getPrimaryEmotion(), metadata.getIntensity(), metadata.getLanguage());
            return metadata;

        } catch (Exception ex) {
            log.warn("Emotion analysis failed — applying keyword fallback. Cause: {}", ex.getMessage());
            return keywordFallback(userText);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** Strip markdown fences that some LLMs inject despite instructions */
    private String extractJson(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        // Take first { ... } block
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String escapeJson(String text) {
        return text.replace("\"", "\\\"").replace("\n", " ");
    }

    /**
     * Keyword-based emotion detection used when the LLM call fails.
     * Covers common Hindi and English emotion words.
     */
    private EmotionMetadata keywordFallback(String text) {
        EmotionMetadata meta = new EmotionMetadata();
        String lower = text.toLowerCase();

        if (containsAny(lower, "happy", "joy", "excited", "khush", "anand", "maza")) {
            meta.setPrimaryEmotion("JOY");
            meta.setIntensity(0.7);
        } else if (containsAny(lower, "sad", "cry", "hurt", "udaas", "dukh", "rona")) {
            meta.setPrimaryEmotion("SADNESS");
            meta.setIntensity(0.7);
        } else if (containsAny(lower, "angry", "frustrated", "gussa", "krodh")) {
            meta.setPrimaryEmotion("ANGER");
            meta.setIntensity(0.8);
        } else if (containsAny(lower, "stress", "anxious", "worried", "tension", "pareshaan")) {
            meta.setPrimaryEmotion("STRESS");
            meta.setIntensity(0.7);
        } else if (containsAny(lower, "relax", "calm", "peace", "shanti", "sukoon")) {
            meta.setPrimaryEmotion("RELAXATION");
            meta.setIntensity(0.5);
        } else if (containsAny(lower, "love", "romance", "pyaar", "ishq", "mohabbat")) {
            meta.setPrimaryEmotion("LOVE");
            meta.setIntensity(0.8);
        } else {
            meta.setPrimaryEmotion("RELAXATION");
            meta.setIntensity(0.5);
        }

        meta.setLanguage(detectLanguage(text));
        meta.setSecondaryEmotions(java.util.List.of());
        meta.setMoodKeywords(java.util.List.of("music", "mood"));
        meta.setContextSummary("Fallback emotion detection from keywords.");
        return meta;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private String detectLanguage(String text) {
        // Simple Unicode-range check for Devanagari script
        for (char c : text.toCharArray()) {
            if (c >= 'ऀ' && c <= 'ॿ') return "Hindi";
        }
        return "English";
    }
}
