package com.musicrec.service;

import com.musicrec.dto.EmotionSlotDTO;
import com.musicrec.dto.TrackDTO;

import java.util.*;

/**
 * Splits the Top-N playlist across the Patient's emotions in proportion to how often each
 * emotion appeared in their responses — e.g. 50% SADNESS, 30% NOSTALGIA, 20% CALM over 10
 * slots gives 5 / 3 / 2 songs — instead of letting the dominant emotion fill every slot.
 *
 * Pure code, no LLM: the caller passes candidates already in preference order (the SELECT
 * stage's picks first, then the rest of the fused pool), and this only decides which of them
 * fill which emotion's share. Each share may have its own raga (one raga per emotion — "multiple
 * emotions, multiple raagas"). Shares are filled in passes, each pass across all emotions, so a
 * strong match for one share is never used up as a weak match for another:
 *   1. primary emotion matches AND the song is in the share's raga
 *   2. primary emotion matches
 *   3. one of the song's secondary emotions matches
 *   4. the song is in the share's raga (a raga traditionally associated with that emotion)
 * Slots that no matching song can fill go to the best remaining candidates and are reported as a
 * shortfall.
 *
 * The playlist is ordered dominant emotion first (largest share), each share in preference
 * order, followed by any general-fit fillers.
 */
public final class EmotionQuotaAllocator {

    private EmotionQuotaAllocator() {}

    public record Allocation(List<TrackDTO> tracks, List<EmotionSlotDTO> slots) {}

    /**
     * @param candidates   tracks in preference order (best first); the list is not modified, but
     *                     each chosen track's matchedEmotion is set
     * @param distribution percent of Patient responses per emotion (EmotionalVariabilityDTO)
     * @param topN         playlist size
     */
    public static Allocation allocate(List<TrackDTO> candidates, Map<String, Double> distribution, int topN) {
        return allocate(candidates, distribution, Map.of(), topN);
    }

    /**
     * @param ragaByEmotion the raga chosen for each emotion's share (emotion → raga); emotions without
     *                      an entry are filled by emotion match only
     */
    public static Allocation allocate(List<TrackDTO> candidates, Map<String, Double> distribution,
                                      Map<String, String> ragaByEmotion, int topN) {
        if (distribution == null || distribution.isEmpty() || candidates.isEmpty()) {
            return new Allocation(candidates.stream().limit(topN).toList(), List.of());
        }

        Map<String, Double> shares = normalizedShares(distribution);
        Map<String, Integer> targets = slotTargets(shares, topN);

        Map<String, List<TrackDTO>> picked = new LinkedHashMap<>();
        targets.keySet().forEach(e -> picked.put(e, new ArrayList<>()));
        Set<Long> used = new HashSet<>();

        Map<String, String> ragas = upperKeys(ragaByEmotion);
        for (int pass = 1; pass <= 4; pass++) {
            for (Map.Entry<String, Integer> target : targets.entrySet()) {
                String emotion = target.getKey();
                String raga = ragas.get(emotion);
                List<TrackDTO> bucket = picked.get(emotion);
                for (TrackDTO t : candidates) {
                    if (bucket.size() >= target.getValue()) break;
                    if (used.contains(t.getId())) continue;
                    if (matchesPass(pass, t, emotion, raga)) {
                        bucket.add(t);
                        used.add(t.getId());
                    }
                }
            }
        }

        List<TrackDTO> playlist = new ArrayList<>();
        List<EmotionSlotDTO> slots = new ArrayList<>();
        for (Map.Entry<String, Integer> target : targets.entrySet()) {
            String emotion = target.getKey();
            List<TrackDTO> bucket = picked.get(emotion);
            bucket.forEach(t -> t.setMatchedEmotion(emotion));
            playlist.addAll(bucket);
            slots.add(new EmotionSlotDTO(emotion, round1(shares.get(emotion) * 100), target.getValue(), bucket.size(),
                    ragas.get(emotion)));
        }

        // Shortfall — fill the remaining slots with the best unused candidates (general fit).
        for (TrackDTO t : candidates) {
            if (playlist.size() >= topN) break;
            if (used.add(t.getId())) {
                t.setMatchedEmotion(null);
                playlist.add(t);
            }
        }
        return new Allocation(playlist, slots);
    }

    /**
     * Largest-remainder (Hamilton) apportionment of topN slots by share, so the slot counts always
     * sum to exactly topN. Ties on the remainder go to the larger share. Ordered by share, largest
     * first; emotions whose share rounds to 0 slots are kept out of the playlist split.
     */
    static Map<String, Integer> slotTargets(Map<String, Double> shares, int topN) {
        List<Map.Entry<String, Double>> ordered = new ArrayList<>(shares.entrySet());
        ordered.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        Map<String, Integer> slots = new LinkedHashMap<>();
        Map<String, Double> remainders = new HashMap<>();
        int assigned = 0;
        for (Map.Entry<String, Double> e : ordered) {
            double exact = e.getValue() * topN;
            int floor = (int) Math.floor(exact);
            slots.put(e.getKey(), floor);
            remainders.put(e.getKey(), exact - floor);
            assigned += floor;
        }
        List<String> byRemainder = new ArrayList<>(slots.keySet());
        byRemainder.sort(Comparator.<String>comparingDouble(remainders::get).reversed()
                .thenComparing(Comparator.<String>comparingDouble(shares::get).reversed()));
        for (int i = 0; assigned < topN && i < byRemainder.size(); i++, assigned++) {
            slots.merge(byRemainder.get(i), 1, Integer::sum);
        }

        Map<String, Integer> nonZero = new LinkedHashMap<>();
        slots.forEach((e, n) -> { if (n > 0) nonZero.put(e, n); });
        return nonZero;
    }

    /** Percent values → shares summing to 1.0, emotion keys upper-cased. */
    private static Map<String, Double> normalizedShares(Map<String, Double> distribution) {
        double total = distribution.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Double> shares = new LinkedHashMap<>();
        if (total <= 0) return shares;
        distribution.forEach((e, pct) -> shares.merge(e.toUpperCase(), pct / total, Double::sum));
        return shares;
    }

    private static boolean matchesPass(int pass, TrackDTO t, String emotion, String raga) {
        return switch (pass) {
            case 1 -> matchesPrimary(t, emotion) && inRaga(t, raga);
            case 2 -> matchesPrimary(t, emotion);
            case 3 -> matchesSecondary(t, emotion);
            default -> inRaga(t, raga);
        };
    }

    private static boolean inRaga(TrackDTO t, String raga) {
        return raga != null && raga.equalsIgnoreCase(t.getRaga());
    }

    private static Map<String, String> upperKeys(Map<String, String> m) {
        Map<String, String> out = new HashMap<>();
        if (m != null) m.forEach((k, v) -> out.put(k.toUpperCase(), v));
        return out;
    }

    private static boolean matchesPrimary(TrackDTO t, String emotion) {
        return emotion.equalsIgnoreCase(t.getPrimaryEmotion());
    }

    private static boolean matchesSecondary(TrackDTO t, String emotion) {
        return t.getSecondaryEmotions() != null
                && t.getSecondaryEmotions().stream().anyMatch(emotion::equalsIgnoreCase);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
