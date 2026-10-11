package com.musicrec.audio;

import com.musicrec.dto.TranscriptSegmentDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpeakerDiarizationServiceTest {

    private static final int SAMPLE_RATE = 16_000;

    private final AcousticFeatureExtractor extractor = new AcousticFeatureExtractor();
    private final SpeakerDiarizationService diarizationService = new SpeakerDiarizationService(extractor);

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
    void separatesTwoClearlyDistinctVoicesIntoTwoClusters() {
        // Alternate a low-pitch/quiet "voice A" and a high-pitch/loud "voice B" across 4 segments —
        // a simple but genuine stand-in for two acoustically distinct speakers.
        double segmentSeconds = 1.0;
        short[] voiceA = sineWave(100.0, 6000, segmentSeconds);
        short[] voiceB = sineWave(300.0, 18000, segmentSeconds);

        short[] fullClip = concat(voiceA, voiceB, voiceA, voiceB);
        List<AcousticFeatureExtractor.Frame> frames = extractor.analyze(fullClip);

        List<TranscriptSegmentDTO> segments = List.of(
                new TranscriptSegmentDTO(0, 0, 1000, "segment A1"),
                new TranscriptSegmentDTO(1, 1000, 2000, "segment B1"),
                new TranscriptSegmentDTO(2, 2000, 3000, "segment A2"),
                new TranscriptSegmentDTO(3, 3000, 4000, "segment B2"));

        SpeakerDiarizationService.DiarizationResult result = diarizationService.diarize(frames, segments);

        assertThat(result.reliable()).isTrue();
        assertThat(result.segments()).hasSize(4);

        int clusterA1 = clusterFor(result, 0);
        int clusterB1 = clusterFor(result, 1);
        int clusterA2 = clusterFor(result, 2);
        int clusterB2 = clusterFor(result, 3);

        // The two "A" segments should land in the same cluster, the two "B" segments in the
        // other cluster, and the two clusters should differ from each other.
        assertThat(clusterA1).isEqualTo(clusterA2);
        assertThat(clusterB1).isEqualTo(clusterB2);
        assertThat(clusterA1).isNotEqualTo(clusterB1);
    }

    @Test
    void loudnessVaryingWithinASpeakerDoesNotOverrideTheirPitch() {
        // Mirrors a real two-voice recording: the low voice's 1st and last turns were louder than
        // its middle turns, matching the high voice's loudness. Pitch must still decide the speaker.
        short[] fullClip = concat(
                sineWave(112.0, 17000, 1.0),   // A, loud
                sineWave(178.0, 14500, 1.0),   // B
                sineWave(110.0, 14800, 1.0),   // A, quiet
                sineWave(176.0, 17400, 1.0),   // B
                sineWave(111.0, 13600, 1.0),   // A, quiet
                sineWave(177.0, 17500, 1.0),   // B
                sineWave(117.0, 17000, 1.0));  // A, loud
        List<AcousticFeatureExtractor.Frame> frames = extractor.analyze(fullClip);

        List<TranscriptSegmentDTO> segments = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) segments.add(new TranscriptSegmentDTO(i, i * 1000L, (i + 1) * 1000L, "segment " + i));

        SpeakerDiarizationService.DiarizationResult result = diarizationService.diarize(frames, segments);

        assertThat(result.reliable()).isTrue();
        int a = clusterFor(result, 0);
        int b = clusterFor(result, 1);
        assertThat(a).isNotEqualTo(b);
        assertThat(List.of(2, 4, 6)).allSatisfy(i -> assertThat(clusterFor(result, i)).isEqualTo(a));
        assertThat(List.of(3, 5)).allSatisfy(i -> assertThat(clusterFor(result, i)).isEqualTo(b));
    }

    @Test
    void oneVoiceWithNaturalPitchVariationIsNotAcceptedAsTwoSpeakers() {
        // Per-turn mean pitches measured from a real recording of ONE voice reading both roles.
        // 2-means will always split it somewhere; that split must not be reported as reliable.
        double[] pitches = {111.8, 114.4, 120.6, 117.0, 128.2, 110.3, 128.2, 133.0, 109.1};
        short[][] parts = new short[pitches.length][];
        List<TranscriptSegmentDTO> segments = new java.util.ArrayList<>();
        for (int i = 0; i < pitches.length; i++) {
            parts[i] = sineWave(pitches[i], 12000, 1.0);
            segments.add(new TranscriptSegmentDTO(i, i * 1000L, (i + 1) * 1000L, "segment " + i));
        }

        SpeakerDiarizationService.DiarizationResult result =
                diarizationService.diarize(extractor.analyze(concat(parts)), segments);

        assertThat(result.reliable()).isFalse();
        assertThat(result.method()).containsIgnoringCase("pitch");
    }

    @Test
    void singleSegmentIsNotMarkedReliable() {
        List<TranscriptSegmentDTO> segments = List.of(new TranscriptSegmentDTO(0, 0, 1000, "only segment"));
        SpeakerDiarizationService.DiarizationResult result = diarizationService.diarize(List.of(), segments);

        assertThat(result.reliable()).isFalse();
        assertThat(result.segments()).hasSize(1);
    }

    @Test
    void emptySegmentListReturnsEmptyUnreliableResult() {
        SpeakerDiarizationService.DiarizationResult result = diarizationService.diarize(List.of(), List.of());
        assertThat(result.reliable()).isFalse();
        assertThat(result.segments()).isEmpty();
    }

    private int clusterFor(SpeakerDiarizationService.DiarizationResult result, int order) {
        return result.segments().stream()
                .filter(s -> s.segment().order() == order)
                .findFirst()
                .orElseThrow()
                .clusterId();
    }

    private short[] concat(short[]... parts) {
        int total = 0;
        for (short[] p : parts) total += p.length;
        short[] out = new short[total];
        int offset = 0;
        for (short[] p : parts) {
            System.arraycopy(p, 0, out, offset, p.length);
            offset += p.length;
        }
        return out;
    }

    @Test
    void aSilentSegmentDoesNotBreakSeparationOfTwoClearVoices() {
        // Reproduces a live failure: per-segment pitch/energy measured from a real 14-turn recording, plus
        // the pitchless "you" Whisper hallucinated over the silent tail. That one segment became its
        // own k-means cluster and collapsed a perfect 2-voice split (pitch ratio reported as 1.00x).
        double[][] turns = { // {pitchHz, rmsEnergy}
                {141.5, 2922}, {265.1, 1993}, {140.3, 3763}, {258.8, 2018}, {146.0, 3239}, {275.1, 1759}, {138.7, 3021},
                {262.7, 1859}, {133.5, 3362}, {253.8, 1549}, {131.4, 4702}, {256.0, 1875}, {133.0, 3290}, {265.5, 1601}};
        short[][] parts = new short[turns.length + 1][];
        List<TranscriptSegmentDTO> segments = new java.util.ArrayList<>();
        for (int i = 0; i < turns.length; i++) {
            parts[i] = sineWave(turns[i][0], turns[i][1] * Math.sqrt(2), 1.0); // sine RMS = amplitude / sqrt(2)
            segments.add(new TranscriptSegmentDTO(i, i * 1000L, (i + 1) * 1000L, "segment " + i));
        }
        parts[turns.length] = new short[SAMPLE_RATE]; // silent tail; the live segment started ~0.6 s after speech ended
        segments.add(new TranscriptSegmentDTO(turns.length, turns.length * 1000L + 300, turns.length * 1000L + 900, "you"));

        SpeakerDiarizationService.DiarizationResult result = diarizationService.diarize(extractor.analyze(concat(parts)), segments);

        assertThat(result.reliable()).isTrue();
        int consultant = clusterFor(result, 0), patient = clusterFor(result, 1);
        assertThat(consultant).isNotEqualTo(patient);
        for (int i = 0; i < turns.length; i++) {
            assertThat(clusterFor(result, i)).isEqualTo(i % 2 == 0 ? consultant : patient);
        }
        assertThat(clusterFor(result, turns.length)).isIn(0, 1);
    }

    @Test
    void aSingleOutlierSegmentIsNotTrustedAsASecondSpeaker() {
        // One voice plus one unusually high segment (e.g. a laugh): 2-means isolates the outlier and
        // its pitch ratio clears 1.2x, but one segment is not enough evidence of a second speaker.
        double[] pitches = {115, 118, 112, 120, 116, 300, 114};
        short[][] parts = new short[pitches.length][];
        List<TranscriptSegmentDTO> segments = new java.util.ArrayList<>();
        for (int i = 0; i < pitches.length; i++) {
            parts[i] = sineWave(pitches[i], 12000, 1.0);
            segments.add(new TranscriptSegmentDTO(i, i * 1000L, (i + 1) * 1000L, "segment " + i));
        }

        SpeakerDiarizationService.DiarizationResult result = diarizationService.diarize(extractor.analyze(concat(parts)), segments);

        assertThat(result.reliable()).isFalse();
    }
}
