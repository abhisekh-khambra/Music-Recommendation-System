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

    /**
     * The emotion used for this response: the text prediction fused with the voice prediction when a
     * voice emotion was available (SpeechEmotionFusion), otherwise the text prediction.
     */
    private String primaryEmotion;
    private double intensity;
    /** Confidence of the text prediction. */
    private double confidence;

    /** Text-based prediction from the words (EmotionAnalysisService.analyzeBatch). */
    private String textEmotion;

    /** Speech-emotion model's prediction from the voice (neutral/happy/angry/sad), null when unavailable. */
    private String voiceEmotion;
    private Double voiceEmotionConfidence;

    /** "text" or "text+audio" — what primaryEmotion was derived from. */
    private String emotionSource;
    private String fusionNote;

    /** Audio-based signal for the same time window, or null if acoustic analysis wasn't available. */
    private String audioArousalLevel;
    private Double audioConfidence;
}
