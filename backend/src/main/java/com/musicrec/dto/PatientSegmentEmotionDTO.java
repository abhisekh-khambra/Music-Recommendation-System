package com.musicrec.dto;

import lombok.Builder;
import lombok.Data;

/**
 * One Patient speech segment's independently-analyzed emotion — a model
 * PREDICTION, not a confirmed clinical state (see EmotionalVariabilityDTO).
 * startMs/endMs are Whisper's real decoded timestamps for this segment, and
 * audioArousalLevel/audioConfidence come from the acoustic analysis of the
 * same segment's audio (see com.musicrec.audio), so a caller can see exactly
 * where text and audio evidence agree or diverge.
 */
@Data
@Builder
public class PatientSegmentEmotionDTO {
    private int order;
    private long startMs;
    private long endMs;
    private String text;

    /** Text-based prediction (EmotionAnalysisService.analyzeBatch). */
    private String primaryEmotion;
    private double intensity;
    private double confidence;

    /** Audio-based signal for the same time window, or null if acoustic analysis wasn't available. */
    private String audioArousalLevel;
    private Double audioConfidence;
}
