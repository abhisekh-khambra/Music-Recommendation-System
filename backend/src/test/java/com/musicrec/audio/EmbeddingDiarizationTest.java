package com.musicrec.audio;

import com.musicrec.dto.TranscriptSegmentDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Speaker separation by voiceprint (speaker embeddings) — needed because pitch alone cannot tell two
 * same-gender voices apart (measured: two male voices were flagged uncertain by the pitch method).
 */
class EmbeddingDiarizationTest {

    private final SpeakerDiarizationService service = new SpeakerDiarizationService(new AcousticFeatureExtractor());

    private List<TranscriptSegmentDTO> segments(int n) {
        List<TranscriptSegmentDTO> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new TranscriptSegmentDTO(i, i * 2000L, i * 2000L + 1800, "turn " + i));
        return out;
    }

    /** A unit vector near `base` with a small deterministic wobble. */
    private double[] voice(double[] base, int seed) {
        double[] v = Arrays.copyOf(base, base.length);
        for (int i = 0; i < v.length; i++) v[i] += 0.05 * Math.sin(seed * 7.3 + i);
        double norm = Math.sqrt(Arrays.stream(v).map(x -> x * x).sum());
        for (int i = 0; i < v.length; i++) v[i] /= norm;
        return v;
    }

    private static final double[] SPEAKER_A = {1, 0.2, 0, 0.1};
    private static final double[] SPEAKER_B = {0.1, 1, 0.3, 0};

    private int[] clusters(SpeakerDiarizationService.DiarizationResult r) {
        return r.segments().stream().mapToInt(SpeakerDiarizationService.DiarizedSegment::clusterId).toArray();
    }

    @Test
    void twoDistinctVoiceprintsAreSeparatedReliably() {
        List<double[]> emb = List.of(voice(SPEAKER_A, 1), voice(SPEAKER_B, 2), voice(SPEAKER_A, 3),
                voice(SPEAKER_B, 4), voice(SPEAKER_A, 5), voice(SPEAKER_B, 6));

        var r = service.diarizeByEmbeddings(segments(6), emb, List.of());

        int[] c = clusters(r);
        assertThat(r.reliable()).isTrue();
        assertThat(c[0]).isEqualTo(c[2]).isEqualTo(c[4]);
        assertThat(c[1]).isEqualTo(c[3]).isEqualTo(c[5]).isNotEqualTo(c[0]);
        assertThat(r.method()).containsIgnoringCase("voiceprint");
    }

    @Test
    void oneVoiceReadingBothRolesIsFlaggedUncertain() {
        List<double[]> emb = new ArrayList<>();
        for (int i = 0; i < 6; i++) emb.add(voice(SPEAKER_A, i));

        assertThat(service.diarizeByEmbeddings(segments(6), emb, List.of()).reliable()).isFalse();
    }

    @Test
    void aSingleOddSegmentIsNotASecondSpeaker() {
        List<double[]> emb = new ArrayList<>();
        for (int i = 0; i < 5; i++) emb.add(voice(SPEAKER_A, i));
        emb.add(voice(SPEAKER_B, 9));

        assertThat(service.diarizeByEmbeddings(segments(6), emb, List.of()).reliable()).isFalse();
    }

    @Test
    void segmentsWithoutAVoiceprintInheritTheNearestSpeaker() {
        List<double[]> emb = new ArrayList<>(List.of(voice(SPEAKER_A, 1), voice(SPEAKER_B, 2), voice(SPEAKER_A, 3),
                voice(SPEAKER_B, 4)));
        emb.add(null);   // too short for a voiceprint, right after a SPEAKER_B segment

        int[] c = clusters(service.diarizeByEmbeddings(segments(5), emb, List.of()));

        assertThat(c[4]).isEqualTo(c[3]);
    }

    @Test
    void tooFewVoiceprintsIsUncertain() {
        List<double[]> emb = new ArrayList<>();
        emb.add(voice(SPEAKER_A, 1));
        emb.add(null);
        emb.add(null);

        assertThat(service.diarizeByEmbeddings(segments(3), emb, List.of()).reliable()).isFalse();
    }
}
