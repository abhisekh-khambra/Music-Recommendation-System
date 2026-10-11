package com.musicrec.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AcousticFeatureExtractorTest {

    private static final int SAMPLE_RATE = 16_000;

    private final AcousticFeatureExtractor extractor = new AcousticFeatureExtractor();

    private short[] sineWave(double freqHz, double amplitude, double durationSeconds) {
        int n = (int) (SAMPLE_RATE * durationSeconds);
        short[] samples = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            samples[i] = (short) Math.round(amplitude * Math.sin(2 * Math.PI * freqHz * t));
        }
        return samples;
    }

    @Test
    void detectsPitchOfACleanSineWaveWithinToleranceOfItsTrueFrequency() {
        // A 150Hz tone sits squarely in the human-voice pitch range this extractor targets.
        short[] samples = sineWave(150.0, 12000, 1.0);

        List<AcousticFeatureExtractor.Frame> frames = extractor.analyze(samples);

        assertThat(frames).isNotEmpty();
        List<AcousticFeatureExtractor.Frame> voiced = frames.stream().filter(AcousticFeatureExtractor.Frame::voiced).toList();
        assertThat(voiced).isNotEmpty();

        double meanPitch = voiced.stream().mapToDouble(AcousticFeatureExtractor.Frame::pitchHz).average().orElse(0);
        assertThat(meanPitch).isCloseTo(150.0, org.assertj.core.data.Offset.offset(5.0));
    }

    @Test
    void doesNotReportHalfThePitchWhenTwiceThePeriodCorrelatesAsStrongly() {
        // 177Hz was previously read as ~88Hz (octave error) because lag 2T scored marginally higher than T
        for (double freq : new double[]{120.0, 177.0, 220.0, 300.0}) {
            AcousticFeatureExtractor.Summary s = extractor.summarize(extractor.analyze(sineWave(freq, 12000, 1.0)));
            assertThat(s.meanPitchHz()).as("pitch of %sHz tone", freq)
                    .isCloseTo(freq, org.assertj.core.data.Offset.offset(freq * 0.05));
        }
    }

    @Test
    void containsSpeechIsFalseForSilenceAndTrueForVoicedAudio() {
        assertThat(AcousticFeatureExtractor.containsSpeech(extractor.analyze(new short[16_000 * 3]))).isFalse();
        assertThat(AcousticFeatureExtractor.containsSpeech(extractor.analyze(sineWave(150.0, 12000, 1.0)))).isTrue();
        assertThat(AcousticFeatureExtractor.containsSpeech(List.of())).isFalse();
    }

    @Test
    void silentAudioProducesNoVoicedFrames() {
        short[] silence = new short[SAMPLE_RATE]; // all zeros
        List<AcousticFeatureExtractor.Frame> frames = extractor.analyze(silence);

        assertThat(frames).isNotEmpty();
        assertThat(frames).allMatch(f -> !f.voiced());
    }

    @Test
    void summarizeOverEmptyFrameListReturnsEmptySummaryRatherThanThrowing() {
        AcousticFeatureExtractor.Summary summary = extractor.summarize(List.of());
        assertThat(summary.frameCount()).isZero();
        assertThat(summary.meanPitchHz()).isZero();
    }

    @Test
    void higherAmplitudeToneHasHigherEnergyThanQuieterTone() {
        short[] loud = sineWave(150.0, 15000, 0.5);
        short[] quiet = sineWave(150.0, 1000, 0.5);

        AcousticFeatureExtractor.Summary loudSummary = extractor.summarize(extractor.analyze(loud));
        AcousticFeatureExtractor.Summary quietSummary = extractor.summarize(extractor.analyze(quiet));

        assertThat(loudSummary.meanEnergy()).isGreaterThan(quietSummary.meanEnergy());
    }

    @Test
    void containsSpeechWithinAWindowOnlyCountsFramesInsideIt() {
        // 1 s of tone followed by 1 s of silence
        short[] clip = new short[SAMPLE_RATE * 2];
        System.arraycopy(sineWave(150.0, 12000, 1.0), 0, clip, 0, SAMPLE_RATE);
        List<AcousticFeatureExtractor.Frame> frames = extractor.analyze(clip);

        assertThat(AcousticFeatureExtractor.containsSpeech(frames, 0, 1000)).isTrue();
        assertThat(AcousticFeatureExtractor.containsSpeech(frames, 1100, 2000)).isFalse();
    }
}
