package com.musicrec.audio;

import com.musicrec.dto.AudioEmotionProfileDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcousticEmotionAnalysisServiceTest {

    private final AcousticFeatureExtractor extractor = new AcousticFeatureExtractor();
    private final AcousticEmotionAnalysisService service = new AcousticEmotionAnalysisService();

    @Test
    void silenceIsUnknownNotLowArousal() {
        // 3 s of digital silence: frames exist, but none are voiced — there is no voice to measure
        AudioEmotionProfileDTO p = service.analyze(extractor.summarize(extractor.analyze(new short[16_000 * 3])));

        assertThat(p.getArousalLevel()).isEqualTo("UNKNOWN");
        assertThat(p.getConfidence()).isZero();
    }

    @Test
    void voicedAudioIsMeasured() {
        short[] tone = new short[16_000];
        for (int i = 0; i < tone.length; i++) tone[i] = (short) Math.round(12000 * Math.sin(2 * Math.PI * 150 * i / 16_000.0));

        AudioEmotionProfileDTO p = service.analyze(extractor.summarize(extractor.analyze(tone)));

        assertThat(p.getArousalLevel()).isIn("LOW", "MEDIUM", "HIGH");
    }

    private short[] steppedTone(double baseHz, double[] ratios) {
        short[] out = new short[16_000 * ratios.length / 2];
        int per = 8_000;
        for (int k = 0; k < ratios.length; k++) {
            double f = baseHz * ratios[k];
            for (int i = 0; i < per; i++) out[k * per + i] = (short) Math.round(12000 * Math.sin(2 * Math.PI * f * i / 16_000.0));
        }
        return out;
    }

    @Test
    void sameRelativePitchMovementScoresTheSameForALowAndAHighVoice() {
        // A higher voice moves more Hz for the same expressiveness; measuring in Hz scored female
        // voices as more aroused than male voices speaking the same way.
        double[] intonation = {1.0, 1.12, 1.25, 1.12, 1.0, 0.89, 1.0, 1.19};
        AudioEmotionProfileDTO low = service.analyze(extractor.summarize(extractor.analyze(steppedTone(120, intonation))));
        AudioEmotionProfileDTO high = service.analyze(extractor.summarize(extractor.analyze(steppedTone(240, intonation))));

        assertThat(high.getPitchVariationSemitones()).isCloseTo(low.getPitchVariationSemitones(), org.assertj.core.data.Offset.offset(0.15));
        assertThat(high.getArousalScore()).isCloseTo(low.getArousalScore(), org.assertj.core.data.Offset.offset(0.02));
    }
}
