package com.musicrec.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.reference.EmotionLabels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    private static final int MAX_PARSE_ATTEMPTS = 2;

    private static final String EMOTION_USER_TEMPLATE = """
            Analyze the following user message and return ONLY a JSON object with this exact schema:
            {
              "primary_emotion": "<one of: JOY, SADNESS, STRESS, ANGER, RELAXATION, ANXIETY, EXCITEMENT, MELANCHOLY, NOSTALGIA, LOVE, FRUSTRATION, CALM>",
              "secondary_emotions": ["<emotion1>", "<emotion2>"],
              "intensity": <decimal number from 0.0 to 1.0, written in digits, e.g. 0.7>,
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

        // The LLM occasionally emits malformed JSON (seen live: "intensity": 0. nine), so a reply
        // that fails to parse gets one retry before falling back to keyword detection.
        for (int attempt = 1; attempt <= MAX_PARSE_ATTEMPTS; attempt++) {
            String rawJson;
            try {
                rawJson = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
            } catch (Exception ex) {
                log.warn("Emotion analysis failed — applying keyword fallback. Cause: {}", ex.getMessage());
                return keywordFallback(userText);
            }

            log.debug("Groq emotion raw response: {}", rawJson);

            try {
                EmotionMetadata metadata = objectMapper.readValue(repairSpelledDigits(extractJson(rawJson)), EmotionMetadata.class);
                normalizeLabels(metadata, userText);
                log.info("Detected emotion: {} (intensity={}) [{}]",
                        metadata.getPrimaryEmotion(), metadata.getIntensity(), metadata.getLanguage());
                return metadata;
            } catch (Exception ex) {
                log.warn("Emotion response could not be parsed (attempt {}/{}). Cause: {}",
                        attempt, MAX_PARSE_ATTEMPTS, ex.getMessage());
            }
        }
        log.warn("Emotion analysis returned malformed JSON on every attempt — applying keyword fallback.");
        return keywordFallback(userText);
    }

    private static final String BATCH_SEGMENT_TEMPLATE = """
            Analyze the emotional content of EACH of the following numbered conversation segments
            independently (do not blend them together — the point is to detect how emotion changes
            across the conversation). For each segment, return ONE JSON object with this schema:
            {
              "index": <segment number>,
              "primary_emotion": "<one of: JOY, SADNESS, STRESS, ANGER, RELAXATION, ANXIETY, EXCITEMENT, MELANCHOLY, NOSTALGIA, LOVE, FRUSTRATION, CALM>",
              "intensity": <decimal number from 0.0 to 1.0, written in digits, e.g. 0.7>,
              "confidence": <0.0 to 1.0, how confident you are given how little text this segment has>
            }

            Segments:
            %s

            Return ONLY this JSON — no markdown, no extra text:
            { "segments": [ <one object per segment above, in the same order> ] }
            """;

    /** One segment's independently-analyzed emotion — used for the per-segment emotional-variability profile. */
    public record SegmentEmotion(int index, String primaryEmotion, double intensity, double confidence) {}

    /**
     * Analyzes emotion for each of several short text segments independently, in a single
     * LLM call (rather than one call per segment) — used to build the segment-level emotional
     * variability profile for the conversation pipeline without per-segment LLM cost blowup.
     */
    public List<SegmentEmotion> analyzeBatch(List<String> segmentTexts) {
        if (segmentTexts == null || segmentTexts.isEmpty()) return List.of();

        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < segmentTexts.size(); i++) {
            numbered.append(i).append(". \"").append(escapeJson(segmentTexts.get(i))).append("\"\n");
        }

        try {
            String prompt = BATCH_SEGMENT_TEMPLATE.formatted(numbered.toString());
            String rawJson = chatClient.prompt().user(prompt).call().content();
            JsonNode root = objectMapper.readTree(repairSpelledDigits(extractJson(rawJson)));

            List<SegmentEmotion> results = new ArrayList<>();
            for (JsonNode node : root.path("segments")) {
                int index = node.path("index").asInt(-1);
                if (index < 0 || index >= segmentTexts.size()) continue;
                String raw = node.path("primary_emotion").asText(null);
                double intensity = node.path("intensity").asDouble(0.5);
                double confidence = node.path("confidence").asDouble(0.5);
                Optional<String> emotion = EmotionLabels.normalize(raw);
                if (emotion.isEmpty()) {
                    // Unrecognised label: fall back to keyword detection for this segment, with low confidence
                    log.warn("Segment {} returned unrecognised emotion label '{}' — using keyword fallback", index, raw);
                    EmotionMetadata m = keywordFallback(segmentTexts.get(index));
                    results.add(new SegmentEmotion(index, m.getPrimaryEmotionSafe(), m.getIntensity(), 0.3));
                } else {
                    results.add(new SegmentEmotion(index, emotion.get(), intensity, confidence));
                }
            }

            if (results.size() != segmentTexts.size()) {
                log.warn("Batch segment emotion analysis returned {} results for {} segments — padding with per-text fallback",
                        results.size(), segmentTexts.size());
                return padMissing(results, segmentTexts);
            }
            return results;

        } catch (Exception ex) {
            log.warn("Batch segment emotion analysis failed — falling back to keyword analysis per segment. Cause: {}", ex.getMessage());
            List<SegmentEmotion> fallback = new ArrayList<>();
            for (int i = 0; i < segmentTexts.size(); i++) {
                EmotionMetadata m = keywordFallback(segmentTexts.get(i));
                fallback.add(new SegmentEmotion(i, m.getPrimaryEmotionSafe(), m.getIntensity(), 0.3));
            }
            return fallback;
        }
    }

    private List<SegmentEmotion> padMissing(List<SegmentEmotion> partial, List<String> segmentTexts) {
        java.util.Map<Integer, SegmentEmotion> byIndex = new java.util.HashMap<>();
        partial.forEach(r -> byIndex.put(r.index(), r));
        List<SegmentEmotion> complete = new ArrayList<>();
        for (int i = 0; i < segmentTexts.size(); i++) {
            complete.add(byIndex.getOrDefault(i, new SegmentEmotion(i, "RELAXATION", 0.5, 0.2)));
        }
        return complete;
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Maps LLM emotion labels onto the canonical set (e.g. "NOSTALGY" → "NOSTALGIA") and drops
     * any it can't recognise. An unrecognised primary emotion is replaced by keyword detection.
     */
    void normalizeLabels(EmotionMetadata metadata, String userText) {
        String raw = metadata.getPrimaryEmotion();
        Optional<String> primary = EmotionLabels.normalize(raw);
        if (primary.isPresent()) {
            if (!primary.get().equals(raw)) log.info("Normalized emotion label '{}' → '{}'", raw, primary.get());
            metadata.setPrimaryEmotion(primary.get());
        } else {
            String fallback = keywordFallback(userText).getPrimaryEmotionSafe();
            log.warn("LLM returned unrecognised emotion label '{}' — using keyword fallback '{}'", raw, fallback);
            metadata.setPrimaryEmotion(fallback);
        }
        metadata.setSecondaryEmotions(EmotionLabels.normalizeAll(metadata.getSecondaryEmotions()));
        metadata.setExpandedEmotions(EmotionLabels.normalizeAll(metadata.getExpandedEmotions()));
    }

    private static final java.util.regex.Pattern SPELLED_DECIMAL = java.util.regex.Pattern.compile(
            "(\\d)\\.\\s*(zero|one|two|three|four|five|six|seven|eight|nine)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final List<String> DIGIT_WORDS =
            List.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine");

    /** Repairs a recurring LLM glitch where a decimal digit is spelled out: "0. nine" → "0.9". */
    static String repairSpelledDigits(String json) {
        java.util.regex.Matcher m = SPELLED_DECIMAL.matcher(json);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, m.group(1) + "." + DIGIT_WORDS.indexOf(m.group(2).toLowerCase()));
        }
        m.appendTail(out);
        return out.toString();
    }

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
        } else if (containsAny(lower, "angry", "anger", "furious", "rage", "irritated", "frustrated", "gussa", "krodh", "naraz")) {
            meta.setPrimaryEmotion("ANGER");
            meta.setIntensity(0.8);
        } else if (containsAny(lower, "anxious", "anxiety", "nervous", "panic", "worried", "ghabrahat", "darr")) {
            meta.setPrimaryEmotion("ANXIETY");
            meta.setIntensity(0.7);
        } else if (containsAny(lower, "stress", "tension", "pressure", "pareshaan")) {
            meta.setPrimaryEmotion("STRESS");
            meta.setIntensity(0.7);
        } else if (containsAny(lower, "nostalg", "miss those", "miss my", "miss the old", "old days", "childhood",
                "yaad", "purane din", "bachpan")) {
            meta.setPrimaryEmotion("NOSTALGIA");
            meta.setIntensity(0.6);
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
