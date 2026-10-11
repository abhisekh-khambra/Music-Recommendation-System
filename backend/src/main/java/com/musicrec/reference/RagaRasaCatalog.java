package com.musicrec.reference;

import java.util.*;

/**
 * Static raga ↔ emotion reference table.
 *
 * Associations here follow Hindustani classical rasa theory and commonly cited
 * raga-chikitsa (music-therapy) literature — they are traditional/heuristic
 * pairings used to widen and explain retrieval, not a clinical or medical
 * claim. Malkauns in particular is flagged as an intensifying raga: useful for
 * guided introspection, but not a substitute for calming a user in acute
 * distress.
 */
public final class RagaRasaCatalog {

    public record RagaProfile(
            String raga,
            String rasa,
            List<String> emotions,
            String timeOfDay,
            String description,
            /** True if this raga intensifies rather than soothes — should be deprioritized
             *  when the detected emotional intensity is high and the state is acute distress
             *  (e.g. STRESS/ANXIETY/ANGER), per the class-level note on Malkauns. */
            boolean cautionAtHighIntensity) {}

    private static final List<RagaProfile> PROFILES = List.of(
            new RagaProfile("Bhairavi", "Karuna (compassion, pathos)",
                    List.of("SADNESS", "MELANCHOLY", "RELAXATION", "CALM"),
                    "Morning / concert-closing",
                    "Traditionally used to evoke compassion and a gentle release of sorrow.",
                    false),

            new RagaProfile("Yaman", "Shringara (romantic devotion), Shanta (peace)",
                    List.of("LOVE", "CALM", "RELAXATION", "NOSTALGIA"),
                    "Evening",
                    "An evening raga associated with serene romance and quiet contentment.",
                    false),

            new RagaProfile("Darbari Kanada", "Karuna (gravitas, pathos)",
                    List.of("MELANCHOLY", "SADNESS"),
                    "Late night",
                    "A weighty, majestic raga traditionally used for deep introspection.",
                    false),

            new RagaProfile("Bageshree", "Shringara (tender longing)",
                    List.of("LOVE", "NOSTALGIA", "MELANCHOLY"),
                    "Night",
                    "Associated with tender romantic longing and night-time reflection.",
                    false),

            new RagaProfile("Malkauns", "Adbhuta / Bhayanaka (intensity, depth)",
                    List.of("ANXIETY", "MELANCHOLY", "STRESS"),
                    "Midnight",
                    "An intense pentatonic raga for deep contemplation — pair with a calming " +
                            "context rather than offering it alone for acute distress.",
                    true),

            new RagaProfile("Ahir Bhairav", "Bhakti (devotion), gentle awakening",
                    List.of("CALM", "RELAXATION", "STRESS", "ANGER"),
                    "Early morning",
                    "A devotional dawn raga traditionally used to settle agitation into calm.",
                    false),

            new RagaProfile("Bhoopali", "Shanta (peace)",
                    List.of("RELAXATION", "CALM"),
                    "Evening",
                    "A simple pentatonic raga known for its soothing, meditative quality.",
                    false),

            new RagaProfile("Hindol", "Hasya / Shringara (joy, festivity)",
                    List.of("JOY", "EXCITEMENT"),
                    "Spring / festive contexts",
                    "A spring raga traditionally linked to joy and vitality.",
                    false),

            new RagaProfile("Bhairav", "Veera / Shanta (solemn grounding)",
                    List.of("STRESS", "ANGER", "FRUSTRATION", "CALM"),
                    "Early morning",
                    "A solemn, grounding raga traditionally used to channel intensity into steadiness.",
                    false),

            new RagaProfile("Todi", "Karuna (delicate pathos)",
                    List.of("MELANCHOLY", "SADNESS", "NOSTALGIA"),
                    "Late morning",
                    "A delicate, introspective raga associated with quiet melancholy.",
                    false),

            new RagaProfile("Kafi", "Shringara (playful romance)",
                    List.of("LOVE", "JOY", "NOSTALGIA"),
                    "Any / spring festivals",
                    "A folk-adjacent raga (thumri, Holi songs) associated with playful romance.",
                    false),

            new RagaProfile("Desh", "Shringara / Karuna (monsoon romance)",
                    List.of("NOSTALGIA", "LOVE", "RELAXATION"),
                    "Monsoon evening",
                    "Associated with monsoon romance and wistful nostalgia.",
                    false),

            new RagaProfile("Bihag", "Shringara (joyous romance)",
                    List.of("LOVE", "JOY"),
                    "Night",
                    "A rich night raga associated with joyous, confident romance.",
                    false),

            new RagaProfile("Marwa", "Karuna / Vairagya (solitude, seriousness)",
                    List.of("MELANCHOLY", "SADNESS", "NOSTALGIA"),
                    "Sunset",
                    "A twilight raga evoking solitude and wistful seriousness.",
                    false),

            new RagaProfile("Puriya Dhanashri", "Shanta (devotional stillness)",
                    List.of("CALM", "MELANCHOLY"),
                    "Twilight",
                    "A deeply contemplative twilight raga used for quiet devotional stillness.",
                    false),

            new RagaProfile("Shivranjani", "Shringara (tender melancholic romance)",
                    List.of("LOVE", "MELANCHOLY", "NOSTALGIA"),
                    "Evening",
                    "A pentatonic raga associated with tender, bittersweet romance.",
                    false),

            new RagaProfile("Bhimpalasi", "Shringara (devotional longing)",
                    List.of("NOSTALGIA", "LOVE"),
                    "Afternoon",
                    "An afternoon raga traditionally used to express devotional longing.",
                    false),

            new RagaProfile("Miyan ki Malhar", "Shringara / Hasya (monsoon longing and joy)",
                    List.of("RELAXATION", "NOSTALGIA", "JOY"),
                    "Monsoon",
                    "A monsoon raga blending yearning with the joy of rain's arrival.",
                    false),

            new RagaProfile("Shree", "Shanta (solemn devotion)",
                    List.of("CALM", "MELANCHOLY"),
                    "Evening",
                    "A grave, devotional evening raga traditionally used for solemn reflection.",
                    false),

            new RagaProfile("Chandrakauns", "Adbhuta (soft intensity, introspection)",
                    List.of("MELANCHOLY", "ANXIETY"),
                    "Midnight",
                    "A modern raga related to Malkauns but softer — introspective without being as intense.",
                    true),

            new RagaProfile("Hansadhwani", "Hasya (bright festive joy)",
                    List.of("JOY", "EXCITEMENT"),
                    "Festive contexts",
                    "A bright, crisp raga (Carnatic-origin, widely used in Hindustani music too) associated with celebration.",
                    false),

            new RagaProfile("Kirwani", "Karuna (bittersweet hope)",
                    List.of("SADNESS", "MELANCHOLY", "CALM"),
                    "Evening / night",
                    "A minor-toned raga blending sorrow with an undertone of hope.",
                    false),

            new RagaProfile("Adana", "Veera (heroic intensity)",
                    List.of("ANGER", "FRUSTRATION", "EXCITEMENT"),
                    "Late night",
                    "A valorous, driving raga traditionally used to channel intensity into heroic resolve.",
                    true)
    );

    private static final Map<String, RagaProfile> BY_RAGA;
    private static final Map<String, List<String>> RAGAS_BY_EMOTION;

    static {
        Map<String, RagaProfile> byRaga = new LinkedHashMap<>();
        Map<String, List<String>> byEmotion = new LinkedHashMap<>();
        for (RagaProfile p : PROFILES) {
            byRaga.put(p.raga().toUpperCase(), p);
            for (String emotion : p.emotions()) {
                byEmotion.computeIfAbsent(emotion.toUpperCase(), k -> new ArrayList<>()).add(p.raga());
            }
        }
        BY_RAGA = Collections.unmodifiableMap(byRaga);
        RAGAS_BY_EMOTION = Collections.unmodifiableMap(byEmotion);
    }

    private RagaRasaCatalog() {}

    public static Optional<RagaProfile> profileFor(String raga) {
        if (raga == null || raga.isBlank()) return Optional.empty();
        return Optional.ofNullable(BY_RAGA.get(raga.toUpperCase()));
    }

    /** True if the raga's traditional rasa/emotion set contains the given emotion. */
    public static boolean ragaMatchesEmotion(String raga, String emotion) {
        if (emotion == null) return false;
        return profileFor(raga)
                .map(p -> p.emotions().contains(emotion.toUpperCase()))
                .orElse(false);
    }

    /** Ragas traditionally associated with any of the given emotions, most-matched first. */
    public static List<String> ragasForEmotions(Collection<String> emotions) {
        if (emotions == null || emotions.isEmpty()) return List.of();

        Map<String, Integer> hitCount = new LinkedHashMap<>();
        for (String emotion : emotions) {
            if (emotion == null) continue;
            for (String raga : RAGAS_BY_EMOTION.getOrDefault(emotion.toUpperCase(), List.of())) {
                hitCount.merge(raga, 1, Integer::sum);
            }
        }
        return hitCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Sum of the weights of the given emotions that this raga is traditionally associated with. */
    public static double coverage(String raga, Map<String, Double> emotionWeights) {
        List<String> ragaEmotions = profileFor(raga).map(RagaProfile::emotions).orElse(List.of());
        double total = 0;
        for (Map.Entry<String, Double> e : emotionWeights.entrySet()) {
            if (e.getKey() != null && ragaEmotions.contains(e.getKey().toUpperCase())) total += e.getValue();
        }
        return total;
    }

    /**
     * Ragas associated with any of the weighted emotions, ranked by {@link #coverage}: the raga
     * covering the largest share of the emotional mix comes first (catalogue order breaks ties).
     */
    public static List<String> ragasForWeightedEmotions(Map<String, Double> emotionWeights) {
        if (emotionWeights == null || emotionWeights.isEmpty()) return List.of();
        return PROFILES.stream()
                .map(RagaProfile::raga)
                .filter(r -> coverage(r, emotionWeights) > 0)
                .sorted(java.util.Comparator.comparingDouble((String r) -> coverage(r, emotionWeights)).reversed())
                .toList();
    }

    /** {@link #ragasForWeightedEmotions} without intensifying ragas — see {@link #ragasForEmotionsExcludingCaution}. */
    public static List<String> ragasForWeightedEmotionsExcludingCaution(Map<String, Double> emotionWeights) {
        List<String> ranked = ragasForWeightedEmotions(emotionWeights);
        List<String> filtered = ranked.stream()
                .filter(r -> !profileFor(r).map(RagaProfile::cautionAtHighIntensity).orElse(false))
                .toList();
        return filtered.isEmpty() ? ranked : filtered;
    }

    /**
     * Same ranking as {@link #ragasForEmotions}, but with intensifying ragas
     * (see {@link RagaProfile#cautionAtHighIntensity()}) filtered out — used
     * when the detected emotional intensity is high and the state reflects
     * acute distress, so an already-intense listener isn't steered toward a
     * further-intensifying raga. Falls back to the unfiltered ranking if
     * filtering would remove every candidate.
     */
    public static List<String> ragasForEmotionsExcludingCaution(Collection<String> emotions) {
        List<String> ranked = ragasForEmotions(emotions);
        List<String> filtered = ranked.stream()
                .filter(r -> !profileFor(r).map(RagaProfile::cautionAtHighIntensity).orElse(false))
                .toList();
        return filtered.isEmpty() ? ranked : filtered;
    }
}
