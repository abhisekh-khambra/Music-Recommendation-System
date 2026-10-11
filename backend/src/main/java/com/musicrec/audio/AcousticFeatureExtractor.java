package com.musicrec.audio;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * From-scratch, pure-Java prosodic feature extraction over 16-bit PCM audio —
 * no trained ML model, no external library. Computes short-time energy and a
 * per-frame fundamental-frequency (pitch) estimate via autocorrelation, which
 * {@link AcousticEmotionAnalysisService} and {@link SpeakerDiarizationService}
 * build on.
 *
 * This is a standard, explainable signal-processing technique (the same family
 * used by classic speech/prosody tools), not a statistical/ML classifier —
 * consistent with the project's choice to avoid a black-box audio model here.
 */
@Component
public class AcousticFeatureExtractor {

    private static final int SAMPLE_RATE = AudioTranscodingService.TARGET_SAMPLE_RATE;
    private static final int FRAME_MS = 25;
    private static final int HOP_MS = 10;
    private static final int FRAME_SIZE = SAMPLE_RATE * FRAME_MS / 1000;   // 400 samples
    private static final int HOP_SIZE = SAMPLE_RATE * HOP_MS / 1000;      // 160 samples

    private static final double MIN_F0_HZ = 80.0;
    private static final double MAX_F0_HZ = 400.0;
    private static final double OCTAVE_TOLERANCE = 0.9;
    /** At least ~100ms of voiced audio before a clip counts as containing speech. */
    private static final int MIN_VOICED_FRAMES_FOR_SPEECH = 10;

    /** One analysis frame: its center timestamp, loudness, and voiced pitch estimate (0 if unvoiced). */
    public record Frame(long timeMs, double rmsEnergy, double pitchHz, boolean voiced) {}

    /** Aggregate acoustic statistics over a span of frames — the unit both arousal scoring and diarization use. */
    /**
     * pitchStdDevSemitones is pitch variability on a log (musical) scale — relative to the speaker's own
     * pitch, so equally expressive low and high voices get the same value (std. dev. in Hz is not).
     */
    public record Summary(double meanPitchHz, double pitchStdDevHz, double meanEnergy,
                           double energyStdDev, double voicedRatio, int frameCount, double pitchStdDevSemitones) {
        public static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Whether the clip contains any voiced speech at all. Used to reject silent uploads before
     * speech-to-text, which otherwise hallucinates text (e.g. "you") from silence.
     */
    public static boolean containsSpeech(List<Frame> frames) {
        return frames.stream().filter(Frame::voiced).count() >= MIN_VOICED_FRAMES_FOR_SPEECH;
    }

    /** {@link #containsSpeech(List)} restricted to frames whose timestamp falls in [startMs, endMs). */
    public static boolean containsSpeech(List<Frame> frames, long startMs, long endMs) {
        return frames.stream().filter(f -> f.voiced() && f.timeMs() >= startMs && f.timeMs() < endMs).count()
                >= MIN_VOICED_FRAMES_FOR_SPEECH;
    }

    /** Runs frame-by-frame analysis over the whole clip. */
    public List<Frame> analyze(short[] pcm) {
        List<Frame> frames = new ArrayList<>();
        if (pcm == null || pcm.length < FRAME_SIZE) return frames;

        // A simple fixed "silence floor" calibrated for 16-bit PCM — frames below it are
        // treated as non-speech regardless of pitch, since autocorrelation gets noisy at low SNR.
        double voicedEnergyFloor = 300.0;

        for (int start = 0; start + FRAME_SIZE <= pcm.length; start += HOP_SIZE) {
            double rms = rms(pcm, start, FRAME_SIZE);
            long timeMs = (long) ((start + FRAME_SIZE / 2.0) / SAMPLE_RATE * 1000);

            double pitch = 0.0;
            boolean voiced = rms >= voicedEnergyFloor;
            if (voiced) {
                pitch = estimatePitchAutocorrelation(pcm, start, FRAME_SIZE);
                voiced = pitch > 0.0;
            }
            frames.add(new Frame(timeMs, rms, pitch, voiced));
        }
        return frames;
    }

    /** Aggregates every frame whose timestamp falls in [startMs, endMs) into one Summary. */
    public Summary summarize(List<Frame> frames, long startMs, long endMs) {
        List<Frame> inWindow = frames.stream()
                .filter(f -> f.timeMs() >= startMs && f.timeMs() < endMs)
                .toList();
        return summarize(inWindow);
    }

    /** Aggregates an already-filtered list of frames into one Summary. */
    public Summary summarize(List<Frame> frames) {
        if (frames.isEmpty()) return Summary.EMPTY;

        double energyMean = frames.stream().mapToDouble(Frame::rmsEnergy).average().orElse(0);
        double energyVar = frames.stream().mapToDouble(f -> Math.pow(f.rmsEnergy() - energyMean, 2)).average().orElse(0);

        List<Frame> voicedFrames = frames.stream().filter(Frame::voiced).toList();
        double pitchMean = voicedFrames.stream().mapToDouble(Frame::pitchHz).average().orElse(0);
        double pitchVar = voicedFrames.isEmpty() ? 0 :
                voicedFrames.stream().mapToDouble(f -> Math.pow(f.pitchHz() - pitchMean, 2)).average().orElse(0);

        double[] semitones = voicedFrames.stream().mapToDouble(f -> 12 * Math.log(f.pitchHz()) / Math.log(2)).toArray();
        double stMean = java.util.Arrays.stream(semitones).average().orElse(0);
        double stVar = java.util.Arrays.stream(semitones).map(x -> (x - stMean) * (x - stMean)).average().orElse(0);

        double voicedRatio = (double) voicedFrames.size() / frames.size();

        return new Summary(pitchMean, Math.sqrt(pitchVar), energyMean, Math.sqrt(energyVar),
                voicedRatio, frames.size(), Math.sqrt(stVar));
    }

    // ── DSP primitives ──────────────────────────────────────────────

    private double rms(short[] pcm, int start, int length) {
        double sumSquares = 0;
        for (int i = start; i < start + length; i++) {
            sumSquares += (double) pcm[i] * pcm[i];
        }
        return Math.sqrt(sumSquares / length);
    }

    /**
     * Estimates fundamental frequency via normalized autocorrelation, restricted to the
     * plausible human voice range (80-400Hz) — the standard, explainable approach for
     * frame-level pitch estimation without a trained model.
     */
    private double estimatePitchAutocorrelation(short[] pcm, int start, int length) {
        int minLag = (int) (SAMPLE_RATE / MAX_F0_HZ);
        int maxLag = (int) (SAMPLE_RATE / MIN_F0_HZ);
        maxLag = Math.min(maxLag, length - 1);
        if (minLag >= maxLag) return 0.0;

        double[] normalized = new double[maxLag + 1];
        double bestCorrelation = 0.0;

        for (int lag = minLag; lag <= maxLag; lag++) {
            double correlation = 0.0;
            double normA = 0.0;
            double normB = 0.0;
            for (int i = 0; i < length - lag; i++) {
                double a = pcm[start + i];
                double b = pcm[start + i + lag];
                correlation += a * b;
                normA += a * a;
                normB += b * b;
            }
            double denom = Math.sqrt(normA * normB);
            normalized[lag] = denom > 0 ? correlation / denom : 0.0;
            bestCorrelation = Math.max(bestCorrelation, normalized[lag]);
        }

        // A periodic signal also correlates at 2x, 3x... its true period, often nearly as strongly,
        // so picking the global maximum can report half the real pitch (an "octave error"). Take
        // the shortest-lag local peak that is within OCTAVE_TOLERANCE of the strongest one instead.
        int bestLag = -1;
        for (int lag = minLag; lag <= maxLag; lag++) {
            boolean localPeak = (lag == minLag || normalized[lag] >= normalized[lag - 1])
                    && (lag == maxLag || normalized[lag] >= normalized[lag + 1]);
            if (localPeak && normalized[lag] >= OCTAVE_TOLERANCE * bestCorrelation) {
                bestLag = lag;
                break;
            }
        }
        if (bestLag > 0) bestCorrelation = normalized[bestLag];

        // Require a reasonably strong periodicity before trusting the estimate — otherwise
        // this frame is treated as unvoiced/noisy rather than reporting a spurious pitch.
        if (bestLag <= 0 || bestCorrelation < 0.3) return 0.0;
        return (double) SAMPLE_RATE / bestLag;
    }
}
