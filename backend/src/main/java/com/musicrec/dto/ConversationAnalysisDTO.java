package com.musicrec.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Non-diagnostic, well-being-oriented summary of the patient's side of a
 * conversation. Every field here is derived deterministically (pure code) in
 * ConversationAnalysisService from the underlying EmotionMetadata — nothing
 * is phrased by an LLM — so the wording is guaranteed to stay in
 * observational/emotional-cue language rather than clinical/diagnostic
 * language (e.g. "conversation indicates elevated stress-related emotional
 * cues", never "the patient has anxiety disorder").
 */
@Data
@Builder
public class ConversationAnalysisDTO {

    /** POSITIVE, NEGATIVE, NEUTRAL, or MIXED — derived from the primary/secondary emotions. */
    private String sentiment;

    private String primaryEmotionalState;
    private List<String> secondaryEmotionalStates;

    /** 0.0 (barely present) .. 1.0 (overwhelming) */
    private double emotionalIntensity;

    private boolean stressCuesDetected;
    private boolean anxietyCuesDetected;
    private boolean sadnessCuesDetected;
    private boolean relaxationCuesDetected;

    private List<String> moodKeywords;
    private String detectedLanguage;

    /** e.g. "Conversation indicates elevated stress-related emotional cues." */
    private String contextSummary;

    /** Always present — a fixed, non-negotiable disclaimer shown alongside the analysis. */
    @Builder.Default
    private String disclaimer =
            "This is an emotional/well-being-oriented analysis of conversational cues, "
            + "not a medical or clinical diagnosis. Consult a qualified professional for health concerns.";
}
