package com.musicrec.audio;

import com.musicrec.dto.AudioEmotionProfileDTO;
import org.springframework.stereotype.Service;

/**
 * Rule-based, explainable scoring of vocal arousal from the acoustic
 * {@link AcousticFeatureExtractor.Summary} statistics — the "audio-based
 * emotion recognition" signal, kept deliberately separate from (and never a
 * silent substitute for) the text-based {@link com.musicrec.service.EmotionAnalysisService}.
 *
 * Deliberately NOT a trained classifier: thresholds below are documented,
 * fixed heuristics over pitch variability and energy, chosen because higher
 * pitch variation + louder/more energetic speech is well-established in
 * prosody research as an arousal (not valence) indicator. This keeps the
 * audio signal fully explainable, at the stated cost of not being able to
 * tell "angry" apart from "excited" from voice alone — both are high-arousal.
 */
@Service
public class AcousticEmotionAnalysisService {

    // Normalization references — NOT calibrated per-microphone/recording; a relative
    // heuristic scale, documented as a limitation rather than treated as an absolute measurement.
    private static final double ENERGY_REFERENCE_MAX = 4.5; // ≈ log10(32767), full 16-bit range
    // Pitch variability is scored in semitones (relative to the speaker's own pitch) rather than Hz:
    // a higher voice moves more Hz for the same expressiveness, so Hz scored women as more aroused
    // than men speaking the same way. ~8 semitones std. dev. ≈ very animated speech.
    private static final double PITCH_STDDEV_REFERENCE_MAX_SEMITONES = 8.0;

    private static final double HIGH_AROUSAL_THRESHOLD = 0.66;
    private static final double MEDIUM_AROUSAL_THRESHOLD = 0.33;

    private static final int MIN_FRAMES_FOR_FULL_CONFIDENCE = 50; // ≈ 0.5s of analyzed audio

    public AudioEmotionProfileDTO analyze(AcousticFeatureExtractor.Summary summary) {
        if (summary.frameCount() == 0 || summary.voicedRatio() == 0.0) {
            return AudioEmotionProfileDTO.builder()
                    .arousalLevel("UNKNOWN")
                    .arousalScore(0.0)
                    .confidence(0.0)
                    .rationale("No usable voiced audio was found in this segment — acoustic arousal could not be estimated.")
                    .build();
        }

        double energyNorm = clamp01(Math.log10(summary.meanEnergy() + 1) / ENERGY_REFERENCE_MAX);
        double pitchVarNorm = clamp01(summary.pitchStdDevSemitones() / PITCH_STDDEV_REFERENCE_MAX_SEMITONES);

        double arousalScore = clamp01(0.5 * energyNorm + 0.5 * pitchVarNorm);
        String level = arousalScore >= HIGH_AROUSAL_THRESHOLD ? "HIGH"
                : arousalScore >= MEDIUM_AROUSAL_THRESHOLD ? "MEDIUM" : "LOW";

        double frameCoverage = clamp01((double) summary.frameCount() / MIN_FRAMES_FOR_FULL_CONFIDENCE);
        double confidence = clamp01(summary.voicedRatio() * 0.7 + frameCoverage * 0.3);

        return AudioEmotionProfileDTO.builder()
                .arousalLevel(level)
                .arousalScore(round2(arousalScore))
                .confidence(round2(confidence))
                .meanPitchHz(round2(summary.meanPitchHz()))
                .pitchVariationHz(round2(summary.pitchStdDevHz()))
                .pitchVariationSemitones(round2(summary.pitchStdDevSemitones()))
                .meanEnergy(round2(summary.meanEnergy()))
                .voicedRatioPct(round2(summary.voicedRatio() * 100))
                .rationale(buildRationale(level, summary))
                .build();
    }

    private String buildRationale(String level, AcousticFeatureExtractor.Summary s) {
        return switch (level) {
            case "HIGH" -> String.format(
                    "Elevated pitch variability (%.1f semitones std. dev) and vocal energy suggest heightened arousal — "
                    + "consistent with states like stress, excitement, or anger. Voiced ratio: %.0f%%.",
                    s.pitchStdDevSemitones(), s.voicedRatio() * 100);
            case "MEDIUM" -> String.format(
                    "Moderate pitch variability (%.1f semitones std. dev) and energy — a mid-range vocal arousal level. "
                    + "Voiced ratio: %.0f%%.",
                    s.pitchStdDevSemitones(), s.voicedRatio() * 100);
            default -> String.format(
                    "Low pitch variability (%.1f semitones std. dev) and vocal energy suggest a calmer, lower-arousal "
                    + "vocal tone — consistent with states like relaxation or sadness. Voiced ratio: %.0f%%.",
                    s.pitchStdDevSemitones(), s.voicedRatio() * 100);
        };
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
