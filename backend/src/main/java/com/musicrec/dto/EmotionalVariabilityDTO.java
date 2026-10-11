package com.musicrec.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Pure-code aggregation (ConversationAnalysisService.buildVariability) over
 * per-segment emotion predictions — req. #3: represent emotional variability
 * across a conversation instead of collapsing it into one fixed emotion.
 * Every field here is a deterministic statistic over `segments`; nothing is
 * phrased by an LLM, and nothing here is a confirmed/diagnosed state.
 */
@Data
@Builder
public class EmotionalVariabilityDTO {

    private List<PatientSegmentEmotionDTO> segments;

    /** How many distinct primary emotions appeared across the Patient's segments. */
    private int distinctEmotionCount;

    /** How many times the primary emotion changed between consecutive segments. */
    private int transitionCount;

    /** Statistical variance of intensity across segments — higher = more volatile. */
    private double intensityVariance;

    /**
     * The emotion detected in the most Patient responses (ties broken by higher total intensity,
     * then by which appeared first). Null when there are no analysed responses.
     */
    private String dominantEmotion;

    /**
     * Share of the Patient's analysed responses showing each emotion, in percent (one decimal,
     * summing to 100), ordered from most to least frequent. Computed from the per-response
     * analysis above — not estimated separately.
     */
    private java.util.Map<String, Double> emotionDistribution;
}
