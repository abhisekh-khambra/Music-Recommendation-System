package com.musicrec.service;

import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.EvidenceRecord;
import com.musicrec.entity.Track;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PROPOSE + ASSIGN stages of the propose-assign-select pipeline.
 *
 * PROPOSE: for each candidate track, generate atomic evidence facts by
 * comparing its real DB fields against the user's emotional profile — pure
 * code, no LLM call, so nothing here can be hallucinated.
 *
 * ASSIGN: group that evidence by track and keep only the strongest few, so
 * the eventual SELECT prompt (Groq) receives a short, verified justification
 * list per track instead of raw fields to reason freely over.
 */
@Service
@Slf4j
public class EvidenceGroundingService {

    private static final int MAX_EVIDENCE_PER_TRACK = 3;

    // ── PROPOSE ──────────────────────────────────────────────────────

    public List<EvidenceRecord> propose(List<Track> candidates,
                                         EmotionMetadata emotion,
                                         Map<Long, Integer> sourceHitCounts) {
        List<EvidenceRecord> evidence = new ArrayList<>();
        for (Track t : candidates) {
            evidence.addAll(evidenceFor(t, emotion, sourceHitCounts.getOrDefault(t.getId(), 0)));
        }
        return evidence;
    }

    private List<EvidenceRecord> evidenceFor(Track t, EmotionMetadata emotion, int sourceHits) {
        List<EvidenceRecord> facts = new ArrayList<>();
        String primary = emotion.getPrimaryEmotionSafe();

        if (t.getPrimaryEmotion() != null && t.getPrimaryEmotion().equalsIgnoreCase(primary)) {
            facts.add(new EvidenceRecord(t.getId(), "primary_emotion_match",
                    "Primary emotion matches the user's " + primary.toLowerCase() + " state", 1.0));
        }

        Set<String> userSecondary = toUpperSet(emotion.getSecondaryEmotions());
        if (t.getSecondaryEmotions() != null) {
            for (String sec : t.getSecondaryEmotions()) {
                if (sec != null && userSecondary.contains(sec.toUpperCase())) {
                    facts.add(new EvidenceRecord(t.getId(), "secondary_emotion_match",
                            "Secondary emotion '" + sec + "' matches the user's profile", 0.7));
                }
            }
        }

        Set<String> expandedEmotions = toUpperSet(emotion.getExpandedEmotions());
        if (t.getSecondaryEmotions() != null) {
            for (String sec : t.getSecondaryEmotions()) {
                if (sec != null && !userSecondary.contains(sec.toUpperCase())
                        && expandedEmotions.contains(sec.toUpperCase())) {
                    facts.add(new EvidenceRecord(t.getId(), "expanded_emotion_match",
                            "Emotion '" + sec + "' is semantically related to the user's mood", 0.5));
                }
            }
        }

        Set<String> moodTagPool = new HashSet<>();
        moodTagPool.addAll(toLowerSet(emotion.getMoodKeywords()));
        moodTagPool.addAll(toLowerSet(emotion.getExpandedMoodTags()));
        if (t.getMoodTags() != null) {
            for (String tag : t.getMoodTags()) {
                if (tag != null && moodTagPool.contains(tag.toLowerCase())) {
                    facts.add(new EvidenceRecord(t.getId(), "mood_tag_match",
                            "Mood tag '" + tag + "' matches the user's mood keywords", 0.6));
                }
            }
        }

        double[] range = valenceEnergyRange(primary);
        if (t.getValence() != null && t.getEnergyLevel() != null) {
            double valence = t.getValence().doubleValue();
            double energy  = t.getEnergyLevel().doubleValue();
            if (valence >= range[0] && valence <= range[1] && energy >= range[2] && energy <= range[3]) {
                facts.add(new EvidenceRecord(t.getId(), "valence_energy_fit",
                        "Valence/energy profile fits the ideal range for " + primary.toLowerCase(), 0.5));
            }
        }

        if (t.getLanguage() != null && t.getLanguage().equalsIgnoreCase(emotion.getLanguageSafe())) {
            facts.add(new EvidenceRecord(t.getId(), "language_match",
                    "Matches the user's detected language (" + t.getLanguage() + ")", 0.4));
        }

        if (sourceHits >= 2) {
            facts.add(new EvidenceRecord(t.getId(), "retrieval_consensus",
                    "Independently surfaced by " + sourceHits + " different retrieval sources",
                    0.3 + 0.1 * sourceHits));
        }

        return facts;
    }

    // ── ASSIGN ───────────────────────────────────────────────────────

    /** Groups evidence by track and keeps only the strongest few per track. */
    public Map<Long, List<EvidenceRecord>> assign(List<EvidenceRecord> evidence) {
        Map<Long, List<EvidenceRecord>> byTrack = evidence.stream()
                .collect(Collectors.groupingBy(EvidenceRecord::trackId));

        byTrack.replaceAll((id, facts) -> facts.stream()
                .sorted(Comparator.comparingDouble(EvidenceRecord::strength).reversed())
                .limit(MAX_EVIDENCE_PER_TRACK)
                .toList());

        return byTrack;
    }

    // ── Shared emotion → audio-feature mapping ─────────────────────

    /** Returns [minValence, maxValence, minEnergy, maxEnergy] for an emotion. */
    public double[] valenceEnergyRange(String emotion) {
        return switch (emotion.toUpperCase()) {
            case "JOY", "EXCITEMENT"         -> new double[]{0.6, 1.0, 0.5, 1.0};
            case "SADNESS", "MELANCHOLY"     -> new double[]{0.0, 0.4, 0.0, 0.5};
            case "ANGER", "FRUSTRATION"      -> new double[]{0.0, 0.4, 0.7, 1.0};
            case "STRESS", "ANXIETY"         -> new double[]{0.0, 0.5, 0.0, 0.6};
            case "RELAXATION", "CALM"        -> new double[]{0.4, 0.8, 0.0, 0.4};
            case "LOVE", "NOSTALGIA"         -> new double[]{0.4, 0.9, 0.1, 0.6};
            default                          -> new double[]{0.3, 0.8, 0.2, 0.8};
        };
    }

    private Set<String> toUpperSet(List<String> list) {
        if (list == null) return Set.of();
        return list.stream().filter(Objects::nonNull).map(String::toUpperCase).collect(Collectors.toSet());
    }

    private Set<String> toLowerSet(List<String> list) {
        if (list == null) return Set.of();
        return list.stream().filter(Objects::nonNull).map(String::toLowerCase).collect(Collectors.toSet());
    }
}
