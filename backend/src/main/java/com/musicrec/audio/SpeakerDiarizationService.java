package com.musicrec.audio;

import com.musicrec.dto.TranscriptSegmentDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Real audio-based speaker diarization: clusters Whisper's time-aligned
 * transcript segments into two speakers using only acoustic features (mean
 * pitch + mean energy per segment), via a small deterministic 2-means
 * clustering — not an LLM, and not text content.
 *
 * This answers "which segments came from the same voice" from the audio
 * signal itself. It does NOT know which cluster is the Health Consultant vs.
 * the Patient — that semantic labeling still needs the conversational-role
 * reasoning in ConversationAnalysisService, applied per acoustic cluster
 * (majority vote) rather than per raw LLM guess. See ConversationController
 * for how the two are combined.
 */
@Service
public class SpeakerDiarizationService {

    private final AcousticFeatureExtractor featureExtractor;

    private static final int MAX_ITERATIONS = 20;

    /**
     * Relative weight of (z-scored) energy vs. pitch in the clustering distance. Mean pitch reflects
     * a speaker's vocal register and is stable across their turns; mean energy mostly reflects how
     * loudly a particular sentence was delivered or how close the speaker was to the mic, so it
     * varies within a speaker. Weighting them equally let a loud consultant turn cluster with the
     * patient on a real two-voice recording, so energy is kept only as a secondary cue.
     */
    static final double ENERGY_WEIGHT = 0.3;

    /**
     * Minimum ratio between the two clusters' mean pitch (~3 semitones) before they are treated as
     * two different speakers. z-scoring makes ANY 2-means split look well separated, so this absolute
     * check is what stops one voice reading both roles (measured: turns 109-133 Hz, best split
     * ratio ~1.14) from being reported as a confident two-speaker split; a male/female pair measured ~1.65.
     */
    static final double MIN_SPEAKER_PITCH_RATIO = 1.2;

    /** From this many pitched segments on, each voice cluster must contain at least two of them. */
    static final int MIN_SEGMENTS_FOR_CLUSTER_SIZE_CHECK = 4;

    public SpeakerDiarizationService(AcousticFeatureExtractor featureExtractor) {
        this.featureExtractor = featureExtractor;
    }

    /** One transcript segment plus the acoustic cluster (0 or 1) it was assigned to. */
    public record DiarizedSegment(TranscriptSegmentDTO segment,
                                   AcousticFeatureExtractor.Summary acoustics,
                                   int clusterId) {}

    public record DiarizationResult(List<DiarizedSegment> segments,
                                     boolean reliable,
                                     String method) {}

    /**
     * @param frames   whole-clip acoustic frames, from AcousticFeatureExtractor.analyze()
     * @param segments Whisper's time-aligned transcript segments
     */
    public DiarizationResult diarize(List<AcousticFeatureExtractor.Frame> frames,
                                      List<TranscriptSegmentDTO> segments) {
        if (segments.isEmpty()) {
            return new DiarizationResult(List.of(), false,
                    "No transcript segments available for diarization.");
        }

        List<AcousticFeatureExtractor.Summary> summaries = segments.stream()
                .map(s -> featureExtractor.summarize(frames, s.startMs(), s.endMs()))
                .toList();

        if (segments.size() == 1) {
            return new DiarizationResult(
                    List.of(new DiarizedSegment(segments.get(0), summaries.get(0), 0)),
                    false,
                    "Only one speech segment detected — not enough data to separate two speakers.");
        }

        // Only segments with measurable pitch take part in clustering. A pitchless segment (silence that
        // speech-to-text hallucinated words over, or a breath) carries no speaker information, and as an
        // extreme z-score outlier it would otherwise claim one of the two clusters for itself.
        int n = segments.size();
        List<Integer> pitched = new ArrayList<>();
        for (int i = 0; i < n; i++) if (summaries.get(i).meanPitchHz() > 0) pitched.add(i);
        int m = pitched.size();

        int[] assignment = new int[n];
        double pitchRatio = 1.0;
        int smallestCluster = 0;
        boolean reliable = false;
        if (m >= 2) {
            // z-score normalize pitch and energy across segments so neither feature dominates
            // just because of its raw scale, down-weight energy (see ENERGY_WEIGHT), then run a
            // deterministic 2-means clustering.
            double[] pitch = pitched.stream().mapToDouble(i -> summaries.get(i).meanPitchHz()).toArray();
            double[] energy = pitched.stream().mapToDouble(i -> summaries.get(i).meanEnergy()).toArray();
            double[] pitchZ = zScore(pitch);
            double[] energyZ = zScore(energy);
            for (int i = 0; i < energyZ.length; i++) energyZ[i] *= ENERGY_WEIGHT;

            int[] pitchedAssignment = kMeans2(pitchZ, energyZ, m);
            for (int k = 0; k < m; k++) assignment[pitched.get(k)] = pitchedAssignment[k];

            pitchRatio = clusterPitchRatio(pitch, pitchedAssignment);
            int ones = 0;
            for (int c : pitchedAssignment) ones += c;
            smallestCluster = Math.min(ones, m - ones);
            // With enough segments, a "speaker" heard only once is more likely an outlier (a laugh, a
            // mis-measured segment) than a second person, so each cluster needs at least two segments.
            int minClusterSize = m >= MIN_SEGMENTS_FOR_CLUSTER_SIZE_CHECK ? 2 : 1;
            reliable = isSeparationReliable(pitchZ, energyZ, pitchedAssignment, m)
                    && pitchRatio >= MIN_SPEAKER_PITCH_RATIO
                    && smallestCluster >= minClusterSize;
        }
        // Pitchless segments inherit the speaker of the nearest segment that had measurable pitch.
        for (int i = 0; i < n; i++) {
            if (summaries.get(i).meanPitchHz() > 0 || m == 0) continue;
            int nearest = pitched.get(0);
            for (int k : pitched) if (Math.abs(k - i) < Math.abs(nearest - i)) nearest = k;
            assignment[i] = assignment[nearest];
        }

        List<DiarizedSegment> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            result.add(new DiarizedSegment(segments.get(i), summaries.get(i), assignment[i]));
        }

        String method = reliable
                ? "Acoustic 2-means clustering over per-segment mean pitch and energy (z-score normalized, pitch-weighted) "
                  + "separated the transcript into two distinct voice clusters."
                : String.format("Acoustic clustering ran but the two voice clusters were not clearly separated "
                  + "(their mean pitch differs by %.2fx, at least %.1fx is required; the smaller voice cluster has %d "
                  + "segment(s)). This happens with one voice reading both roles, two speakers with similar vocal "
                  + "registers, a single unusual segment, or a very short recording — diarization confidence is low.",
                  pitchRatio, MIN_SPEAKER_PITCH_RATIO, smallestCluster);

        return new DiarizationResult(result, reliable, method);
    }

    /**
     * Which separation to use when both ran: reliable voiceprints first (they capture timbre, not just
     * pitch), then reliable pitch clustering; when neither is reliable the voiceprint split is kept and
     * both explanations are reported. No voiceprints → the pitch result as is.
     */
    public static DiarizationResult choose(DiarizationResult pitch, DiarizationResult voiceprint) {
        if (voiceprint == null) return pitch;
        if (voiceprint.reliable()) return voiceprint;
        if (pitch.reliable()) return pitch;
        return new DiarizationResult(voiceprint.segments(), false,
                voiceprint.method() + " Pitch clustering: " + pitch.method());
    }

    // ── Voiceprint (speaker-embedding) diarization ──────────────────

    /**
     * Two voice clusters are only treated as two different speakers when the cosine similarity of
     * their mean voiceprints is at most this. Same-speaker WavLM x-vectors typically score above
     * ~0.86 (the threshold suggested for microsoft/wavlm-base-plus-sv speaker verification).
     */
    static final double MAX_SPEAKER_CENTROID_SIMILARITY = 0.86;

    /**
     * Speaker separation from per-segment voiceprints (speaker embeddings from the speech-emotion
     * service). Unlike pitch, a voiceprint captures timbre, so two voices of the same gender can be
     * told apart. Deterministic 2-means on cosine similarity, seeded by the least similar pair;
     * segments without a voiceprint (too short) inherit the nearest segment's speaker.
     *
     * @param embeddings one voiceprint per segment, aligned with {@code segments}; null entries allowed
     * @param frames     acoustic frames, only used to attach per-segment acoustic summaries
     */
    public DiarizationResult diarizeByEmbeddings(List<TranscriptSegmentDTO> segments, List<double[]> embeddings,
                                                 List<AcousticFeatureExtractor.Frame> frames) {
        int n = segments.size();
        List<AcousticFeatureExtractor.Summary> summaries = segments.stream()
                .map(s -> featureExtractor.summarize(frames, s.startMs(), s.endMs()))
                .toList();
        List<Integer> withVoice = new ArrayList<>();
        for (int i = 0; i < n; i++) if (i < embeddings.size() && embeddings.get(i) != null) withVoice.add(i);
        int m = withVoice.size();

        int[] assignment = new int[n];
        boolean reliable = false;
        double similarity = 1.0;
        int smallest = 0;
        if (m >= 2) {
            double[][] v = withVoice.stream().map(i -> normalize(embeddings.get(i))).toArray(double[][]::new);
            int[] a = cosineKMeans2(v);
            for (int k = 0; k < m; k++) assignment[withVoice.get(k)] = a[k];
            int ones = 0;
            for (int c : a) ones += c;
            smallest = Math.min(ones, m - ones);
            if (smallest > 0) similarity = cosine(meanVector(v, a, 0), meanVector(v, a, 1));
            int minClusterSize = m >= MIN_SEGMENTS_FOR_CLUSTER_SIZE_CHECK ? 2 : 1;
            reliable = smallest >= minClusterSize && similarity <= MAX_SPEAKER_CENTROID_SIMILARITY;
        }
        for (int i = 0; i < n; i++) {
            if (withVoice.contains(i) || m == 0) continue;
            int nearest = withVoice.get(0);
            for (int k : withVoice) if (Math.abs(k - i) < Math.abs(nearest - i)) nearest = k;
            assignment[i] = assignment[nearest];
        }

        List<DiarizedSegment> result = new ArrayList<>();
        for (int i = 0; i < n; i++) result.add(new DiarizedSegment(segments.get(i), summaries.get(i), assignment[i]));

        String method = reliable
                ? String.format("Voiceprint clustering (WavLM speaker embeddings, cosine 2-means) separated the transcript "
                  + "into two distinct voices (similarity between the two voices %.2f, at most %.2f allowed).",
                  similarity, MAX_SPEAKER_CENTROID_SIMILARITY)
                : String.format("Voiceprint clustering ran but did not find two clearly different voices (similarity "
                  + "between the two voice clusters %.2f, at most %.2f allowed; smaller cluster has %d segment(s); "
                  + "%d of %d segments had a voiceprint) — likely one voice, or too little speech.",
                  similarity, MAX_SPEAKER_CENTROID_SIMILARITY, smallest, m, n);
        return new DiarizationResult(result, reliable, method);
    }

    private int[] cosineKMeans2(double[][] v) {
        int n = v.length;
        int seedA = 0, seedB = 1;
        double minSim = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double s = cosine(v[i], v[j]);
                if (s < minSim) { minSim = s; seedA = i; seedB = j; }
            }
        }
        double[] ca = v[seedA], cb = v[seedB];
        int[] assignment = new int[n];
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int c = cosine(v[i], ca) >= cosine(v[i], cb) ? 0 : 1;
                if (assignment[i] != c) changed = true;
                assignment[i] = c;
            }
            ca = meanVector(v, assignment, 0);
            cb = meanVector(v, assignment, 1);
            if (!changed && iter > 0) break;
        }
        return assignment;
    }

    private static double[] meanVector(double[][] v, int[] assignment, int cluster) {
        double[] sum = new double[v[0].length];
        int count = 0;
        for (int i = 0; i < v.length; i++) {
            if (assignment[i] != cluster) continue;
            for (int d = 0; d < sum.length; d++) sum[d] += v[i][d];
            count++;
        }
        if (count > 0) for (int d = 0; d < sum.length; d++) sum[d] /= count;
        return sum;
    }

    private static double[] normalize(double[] x) {
        double norm = 0;
        for (double d : x) norm += d * d;
        norm = Math.sqrt(norm);
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) out[i] = norm > 0 ? x[i] / norm : 0;
        return out;
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    // ── 2-means clustering ──────────────────────────────────────────

    private int[] kMeans2(double[] pitchZ, double[] energyZ, int n) {
        // Deterministic init: farthest-apart pair of segments seeds the two centroids,
        // so the result doesn't depend on insertion order or randomness.
        int seedA = 0, seedB = 1;
        double maxDist = -1;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = distance(pitchZ[i], energyZ[i], pitchZ[j], energyZ[j]);
                if (d > maxDist) {
                    maxDist = d;
                    seedA = i;
                    seedB = j;
                }
            }
        }

        double centroidAx = pitchZ[seedA], centroidAy = energyZ[seedA];
        double centroidBx = pitchZ[seedB], centroidBy = energyZ[seedB];

        int[] assignment = new int[n];
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                double distA = distance(pitchZ[i], energyZ[i], centroidAx, centroidAy);
                double distB = distance(pitchZ[i], energyZ[i], centroidBx, centroidBy);
                int cluster = distA <= distB ? 0 : 1;
                if (assignment[i] != cluster) changed = true;
                assignment[i] = cluster;
            }

            double sumAx = 0, sumAy = 0, sumBx = 0, sumBy = 0;
            int countA = 0, countB = 0;
            for (int i = 0; i < n; i++) {
                if (assignment[i] == 0) { sumAx += pitchZ[i]; sumAy += energyZ[i]; countA++; }
                else { sumBx += pitchZ[i]; sumBy += energyZ[i]; countB++; }
            }
            if (countA > 0) { centroidAx = sumAx / countA; centroidAy = sumAy / countA; }
            if (countB > 0) { centroidBx = sumBx / countB; centroidBy = sumBy / countB; }

            if (!changed && iter > 0) break;
        }
        return assignment;
    }

    private boolean isSeparationReliable(double[] pitchZ, double[] energyZ, int[] assignment, int n) {
        boolean hasBoth = false;
        int first = assignment[0];
        for (int c : assignment) if (c != first) hasBoth = true;
        if (!hasBoth) return false;

        // Reliable if the two clusters are, on average, farther apart than the typical
        // spread within a single cluster (a simple, explainable separation check).
        double[] centroidA = centroid(pitchZ, energyZ, assignment, 0);
        double[] centroidB = centroid(pitchZ, energyZ, assignment, 1);
        double centroidDistance = distance(centroidA[0], centroidA[1], centroidB[0], centroidB[1]);

        double avgWithinSpread = 0;
        int count = 0;
        for (int i = 0; i < n; i++) {
            double[] c = assignment[i] == 0 ? centroidA : centroidB;
            avgWithinSpread += distance(pitchZ[i], energyZ[i], c[0], c[1]);
            count++;
        }
        avgWithinSpread = count > 0 ? avgWithinSpread / count : 0;

        return centroidDistance > avgWithinSpread;
    }

    /** Higher cluster's mean pitch divided by the lower one's (1.0 if either cluster has no pitched segments). */
    private double clusterPitchRatio(double[] pitch, int[] assignment) {
        double[] sum = new double[2];
        int[] count = new int[2];
        for (int i = 0; i < pitch.length; i++) {
            if (pitch[i] > 0) { sum[assignment[i]] += pitch[i]; count[assignment[i]]++; }
        }
        if (count[0] == 0 || count[1] == 0) return 1.0;
        double a = sum[0] / count[0], b = sum[1] / count[1];
        return Math.max(a, b) / Math.min(a, b);
    }

    private double[] centroid(double[] x, double[] y, int[] assignment, int cluster) {
        double sx = 0, sy = 0;
        int count = 0;
        for (int i = 0; i < assignment.length; i++) {
            if (assignment[i] == cluster) { sx += x[i]; sy += y[i]; count++; }
        }
        return count == 0 ? new double[]{0, 0} : new double[]{sx / count, sy / count};
    }

    private double distance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    private double[] zScore(double[] values) {
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;

        double variance = 0;
        for (double v : values) variance += Math.pow(v - mean, 2);
        variance /= values.length;
        double stdDev = Math.sqrt(variance);

        double[] z = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            z[i] = stdDev > 1e-9 ? (values[i] - mean) / stdDev : 0.0;
        }
        return z;
    }
}
