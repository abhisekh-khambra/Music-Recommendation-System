package com.musicrec.evaluation;

import com.musicrec.dto.ConversationTurnDTO;
import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.Track;

import java.util.*;

/**
 * Pure-code ground truth and per-case scores for the conversation evaluation — no LLM, no DB,
 * so every number in an evaluation report can be traced back to these definitions.
 */
public final class EvaluationScoring {

    private EvaluationScoring() {}

    /** Ground-truth emotion labels of the PATIENT turns, in conversation order. */
    public static List<String> patientLabels(EvaluationDataset.Case c) {
        return c.turns().stream()
                .filter(t -> ConversationTurnDTO.PATIENT.equalsIgnoreCase(t.role()))
                .map(t -> t.emotion() == null ? null : t.emotion().strip().toUpperCase())
                .toList();
    }

    /** Percent of labelled Patient turns per emotion, most frequent first (ties: first appearance). */
    public static Map<String, Double> distribution(List<String> labels) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        labels.stream().filter(Objects::nonNull).forEach(l -> counts.merge(l, 1, Integer::sum));
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(counts.entrySet());
        ordered.sort(Map.Entry.<String, Integer>comparingByValue().reversed()); // stable → first appearance wins ties
        Map<String, Double> out = new LinkedHashMap<>();
        ordered.forEach(e -> out.put(e.getKey(), Math.round(e.getValue() * 1000.0 / total) / 10.0));
        return out;
    }

    /**
     * The case's expected dominant emotion: the explicit label if given, otherwise the single most
     * frequent Patient label. Throws on a tie without an explicit label — the case is ambiguous.
     */
    public static String expectedDominant(EvaluationDataset.Case c) {
        if (c.expectedDominantEmotion() != null && !c.expectedDominantEmotion().isBlank()) {
            return c.expectedDominantEmotion().strip().toUpperCase();
        }
        Map<String, Double> dist = distribution(patientLabels(c));
        if (dist.isEmpty()) throw new IllegalArgumentException("Case " + c.id() + " has no labelled Patient turns");
        List<Double> shares = new ArrayList<>(dist.values());
        if (shares.size() > 1 && shares.get(0).equals(shares.get(1))) {
            throw new IllegalArgumentException("Case " + c.id() + " has a tie for the dominant emotion — set expectedDominantEmotion");
        }
        return dist.keySet().iterator().next();
    }

    /**
     * Similarity of two emotion distributions (percent values): 1 − total variation distance,
     * i.e. 1.0 for identical shares and 0.0 for no overlap at all.
     */
    public static double distributionSimilarity(Map<String, Double> expected, Map<String, Double> actual) {
        Set<String> keys = new HashSet<>();
        expected.keySet().forEach(k -> keys.add(k.toUpperCase()));
        actual.keySet().forEach(k -> keys.add(k.toUpperCase()));
        Map<String, Double> e = upper(expected), a = upper(actual);
        double totalE = e.values().stream().mapToDouble(Double::doubleValue).sum();
        double totalA = a.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalE <= 0 || totalA <= 0) return 0.0;
        double tvd = 0;
        for (String k : keys) {
            tvd += Math.abs(e.getOrDefault(k, 0.0) / totalE - a.getOrDefault(k, 0.0) / totalA);
        }
        return round(1 - tvd / 2);
    }

    /**
     * Emotion composition of a playlist by each song's primary emotion, in percent. Songs whose
     * primary emotion is not one of {@code expectedEmotions} are pooled under "OTHER", so a playlist
     * full of off-target songs scores low against the expected distribution.
     */
    public static Map<String, Double> playlistComposition(List<TrackDTO> tracks, Set<String> expectedEmotions) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TrackDTO t : tracks) {
            String e = t.getPrimaryEmotion() == null ? "OTHER" : t.getPrimaryEmotion().toUpperCase();
            counts.merge(expectedEmotions.contains(e) ? e : "OTHER", 1, Integer::sum);
        }
        Map<String, Double> out = new LinkedHashMap<>();
        if (tracks.isEmpty()) return out;
        counts.forEach((e, n) -> out.put(e, n * 100.0 / tracks.size()));
        return out;
    }

    /**
     * Graded relevance of a catalogue song to a case, from its metadata only:
     *   emotion gain 2 — the song's primary emotion is one of the case's expected emotions
     *   emotion gain 1 — otherwise, one of its secondary emotions is
     *   raga gain    1 — the song is set in one of the case's acceptable ragas
     * Total 0–3. 0 means not relevant.
     */
    public static int relevance(Track t, Set<String> expectedEmotions, Collection<String> acceptableRagas) {
        int gain = 0;
        if (t.getPrimaryEmotion() != null && expectedEmotions.contains(t.getPrimaryEmotion().toUpperCase())) {
            gain += 2;
        } else if (t.getSecondaryEmotions() != null
                && t.getSecondaryEmotions().stream().anyMatch(s -> s != null && expectedEmotions.contains(s.toUpperCase()))) {
            gain += 1;
        }
        if (t.getRaga() != null && acceptableRagas.stream().anyMatch(r -> r.equalsIgnoreCase(t.getRaga()))) {
            gain += 1;
        }
        return gain;
    }

    /** Relevance judgment over the whole eligible catalogue (needed for NDCG's ideal ranking and recall). */
    public static Map<Long, Integer> relevanceJudgments(List<Track> eligibleCatalogue, Set<String> expectedEmotions,
                                                        Collection<String> acceptableRagas) {
        Map<Long, Integer> out = new HashMap<>();
        for (Track t : eligibleCatalogue) out.put(t.getId(), relevance(t, expectedEmotions, acceptableRagas));
        return out;
    }

    /** Share of the playlist whose primary emotion is one of the expected emotions. */
    public static double songEmotionAccuracy(List<TrackDTO> tracks, Set<String> expectedEmotions) {
        if (tracks.isEmpty()) return 0.0;
        long hits = tracks.stream().filter(t -> t.getPrimaryEmotion() != null
                && expectedEmotions.contains(t.getPrimaryEmotion().toUpperCase())).count();
        return round((double) hits / tracks.size());
    }

    /** Share of the playlist set in an acceptable raga; songs with no raga tag count as misses. */
    public static double songRagaAccuracy(List<TrackDTO> tracks, Collection<String> acceptableRagas) {
        if (tracks.isEmpty()) return 0.0;
        long hits = tracks.stream().filter(t -> t.getRaga() != null
                && acceptableRagas.stream().anyMatch(r -> r.equalsIgnoreCase(t.getRaga()))).count();
        return round((double) hits / tracks.size());
    }

    /** Share of the playlist that carries any raga tag — context for songRagaAccuracy. */
    public static double ragaTaggedShare(List<TrackDTO> tracks) {
        if (tracks.isEmpty()) return 0.0;
        return round((double) tracks.stream().filter(t -> t.getRaga() != null && !t.getRaga().isBlank()).count()
                / tracks.size());
    }

    /** Share of the playlist in an acceptable genre, or null when the case has no genre labels. */
    public static Double genreAccuracy(List<TrackDTO> tracks, List<String> expectedGenres) {
        if (expectedGenres == null || expectedGenres.isEmpty()) return null;
        if (tracks.isEmpty()) return 0.0;
        long hits = tracks.stream().filter(t -> t.getGenre() != null
                && expectedGenres.stream().anyMatch(g -> g.equalsIgnoreCase(t.getGenre().strip()))).count();
        return round((double) hits / tracks.size());
    }

    /** Human relevance judgments (song title → grade) over the catalogue; unjudged songs get 0. */
    public static Map<Long, Integer> humanRelevance(List<Track> eligibleCatalogue, Map<String, Integer> judgments) {
        Map<String, Integer> byTitle = new HashMap<>();
        judgments.forEach((title, grade) -> byTitle.put(normalizeTitle(title), grade == null ? 0 : grade));
        Map<Long, Integer> out = new HashMap<>();
        for (Track t : eligibleCatalogue) out.put(t.getId(), byTitle.getOrDefault(normalizeTitle(t.getTitle()), 0));
        return out;
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.strip().toLowerCase();
    }

    private static Map<String, Double> upper(Map<String, Double> m) {
        Map<String, Double> out = new HashMap<>();
        m.forEach((k, v) -> out.merge(k.toUpperCase(), v, Double::sum));
        return out;
    }

    static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
