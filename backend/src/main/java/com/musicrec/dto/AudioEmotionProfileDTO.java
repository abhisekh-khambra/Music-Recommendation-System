package com.musicrec.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Audio-based (acoustic/prosodic) emotion signal — distinct from the
 * text-based {@link EmotionMetadata}. Derived purely from pitch, energy, and
 * voicing statistics of the actual audio signal (see
 * com.musicrec.audio.AcousticEmotionAnalysisService), never from the
 * transcript text.
 *
 * Acoustic cues reliably indicate *arousal* (how vocally intense/energetic
 * the speech sounds) but not *valence* (positive vs. negative) or a specific
 * emotion category on their own — that limitation is stated explicitly here
 * rather than overclaiming a precise emotion from voice alone.
 */
@Data
@Builder
public class AudioEmotionProfileDTO {

    /** LOW, MEDIUM, or HIGH vocal arousal. */
    private String arousalLevel;

    /** 0.0-1.0 continuous arousal score the level was thresholded from. */
    private double arousalScore;

    /** 0.0-1.0 — how much usable voiced signal was available to base this estimate on. */
    private double confidence;

    private double meanPitchHz;
    private double pitchVariationHz;
    private double pitchVariationSemitones;
    private double meanEnergy;
    private double voicedRatioPct;

    /** Human-readable justification tying the score back to the acoustic stats above. */
    private String rationale;

    @Builder.Default
    private String limitations =
            "Acoustic analysis estimates vocal arousal (intensity) from pitch and energy only. "
            + "It cannot reliably determine valence (positive/negative) or a specific emotion category "
            + "from voice alone, and is not a clinical or diagnostic measurement.";
}
