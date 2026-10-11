package com.musicrec.reference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The closed set of emotion labels the system uses, plus normalization of the near-miss
 * spellings the LLM occasionally returns despite the prompt schema (e.g. "NOSTALGY" instead
 * of "NOSTALGIA"). Anything that is neither canonical nor a known alias is rejected rather
 * than passed downstream, so Raga mapping and track matching only ever see valid labels.
 */
public final class EmotionLabels {

    public static final Set<String> CANONICAL = Set.of(
            "JOY", "SADNESS", "STRESS", "ANGER", "RELAXATION", "ANXIETY",
            "EXCITEMENT", "MELANCHOLY", "NOSTALGIA", "LOVE", "FRUSTRATION", "CALM");

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("NOSTALGY", "NOSTALGIA"),
            Map.entry("NOSTALGIC", "NOSTALGIA"),
            Map.entry("HAPPY", "JOY"),
            Map.entry("HAPPINESS", "JOY"),
            Map.entry("JOYFUL", "JOY"),
            Map.entry("SAD", "SADNESS"),
            Map.entry("STRESSED", "STRESS"),
            Map.entry("ANGRY", "ANGER"),
            Map.entry("RELAXED", "RELAXATION"),
            Map.entry("ANXIOUS", "ANXIETY"),
            Map.entry("EXCITED", "EXCITEMENT"),
            Map.entry("MELANCHOLIC", "MELANCHOLY"),
            Map.entry("LOVING", "LOVE"),
            Map.entry("FRUSTRATED", "FRUSTRATION"),
            Map.entry("CALMNESS", "CALM"));

    private EmotionLabels() {}

    /** Canonical label for {@code raw}, or empty if it is not a recognised emotion. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        String key = raw.strip().toUpperCase();
        if (CANONICAL.contains(key)) return Optional.of(key);
        return Optional.ofNullable(ALIASES.get(key));
    }

    /** Normalizes each label, dropping unrecognised ones and duplicates (order preserved). */
    public static List<String> normalizeAll(Collection<String> raw) {
        if (raw == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String r : raw) normalize(r).ifPresent(out::add);
        return new ArrayList<>(out);
    }
}
