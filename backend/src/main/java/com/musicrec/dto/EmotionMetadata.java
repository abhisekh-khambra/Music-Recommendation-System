package com.musicrec.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Structured output from Groq LLM emotion analysis.
 * Maps directly from the JSON the LLM returns.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmotionMetadata {

    /**
     * One of: JOY, SADNESS, STRESS, ANGER, RELAXATION, ANXIETY,
     *         EXCITEMENT, MELANCHOLY, NOSTALGIA, LOVE, FRUSTRATION, CALM
     */
    @JsonProperty("primary_emotion")
    private String primaryEmotion;

    /** Secondary emotions the model detected */
    @JsonProperty("secondary_emotions")
    private List<String> secondaryEmotions;

    /** 0.0 = barely present, 1.0 = extremely intense */
    @JsonProperty("intensity")
    private double intensity;

    /** Detected language: "English" or "Hindi" */
    @JsonProperty("language")
    private String language;

    /** Keywords the model extracted that describe the mood */
    @JsonProperty("mood_keywords")
    private List<String> moodKeywords;

    /** Short 1-sentence summary of the emotional context */
    @JsonProperty("context_summary")
    private String contextSummary;

    /**
     * Semantically related emotions beyond the primary/secondary pair.
     * Used to widen retrieval without losing precision.
     * e.g. STRESS → ["ANXIETY", "TENSION", "OVERWHELMED", "RESTLESS"]
     */
    @JsonProperty("expanded_emotions")
    private List<String> expandedEmotions;

    /**
     * Mood descriptor words for tag-based retrieval expansion.
     * e.g. STRESS → ["calming", "peaceful", "meditation", "focus", "breathing"]
     */
    @JsonProperty("expanded_mood_tags")
    private List<String> expandedMoodTags;

    // ── Fallback defaults ────────────────────────────────────────
    public String getPrimaryEmotionSafe() {
        return (primaryEmotion != null && !primaryEmotion.isBlank())
                ? primaryEmotion.toUpperCase()
                : "RELAXATION";
    }

    public String getLanguageSafe() {
        return (language != null && !language.isBlank()) ? language : "English";
    }
}
