package com.musicrec.service;

import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.RagaSelectionDTO;
import com.musicrec.reference.RagaRasaCatalog;
import com.musicrec.reference.RagaRasaCatalog.RagaProfile;
import com.musicrec.repository.TrackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Evidence-aware Raga selection — the central step between a detected
 * emotional/mental state and the final song ranking (requirement: Raga is
 * not just an extra filter, it drives the recommendation).
 *
 * Entirely pure code, no LLM call: the candidate ragas come from the
 * structured, static RagaRasaCatalog (traditional rasa-theory associations),
 * and the final pick additionally checks the live track catalogue so the
 * selection is never a raga with zero recommendable songs. Every field on
 * the returned DTO — detected state, rasa, description, reason — is
 * traceable back to that static reference table, so nothing here can drift
 * into an unsupported or fabricated claim.
 */
@Service
@Slf4j
public class RagaSelectionService {

    private static final double HIGH_INTENSITY_THRESHOLD = 0.7;
    private static final Set<String> ACUTE_DISTRESS_STATES = Set.of("STRESS", "ANXIETY", "ANGER", "FRUSTRATION");

    /** Broadly calming fallback raga used only when the catalogue has no association at all. */
    private static final String DEFAULT_FALLBACK_RAGA = "Yaman";

    private final TrackRepository trackRepository;

    public RagaSelectionService(TrackRepository trackRepository) {
        this.trackRepository = trackRepository;
    }

    public RagaSelectionDTO selectRaga(EmotionMetadata emotion) {
        return selectRaga(emotion, false);
    }

    /**
     * @param restrictToHindi when true (the conversation pipeline's mandatory mode — see
     *                        MusicRecommendationService), "tracksAvailable" and the raga-fallback
     *                        logic only count tracks that are also language=Hindi, so the chosen
     *                        raga is one that can actually produce Hindi recommendations.
     */
    public RagaSelectionDTO selectRaga(EmotionMetadata emotion, boolean restrictToHindi) {
        return selectRaga(emotion, Map.of(), restrictToHindi);
    }

    /**
     * Distribution-aware selection for the conversation pipeline: each raga is scored by how much
     * of the Patient's emotional mix it traditionally covers — the share of analysed responses
     * per emotion (percent), plus {@link #OVERALL_PRIMARY_WEIGHT} for the overall Patient emotion
     * and {@link #RELATED_EMOTION_WEIGHT} for its secondary/related emotions — instead of
     * counting every matching emotion equally. An empty distribution gives the original behaviour.
     *
     * @param emotionDistribution percent of Patient responses per emotion (from EmotionalVariabilityDTO)
     */
    public RagaSelectionDTO selectRaga(EmotionMetadata emotion, Map<String, Double> emotionDistribution,
                                       boolean restrictToHindi) {
        List<String> warnings = new ArrayList<>();
        String primary = emotion.getPrimaryEmotionSafe();
        double intensity = emotion.getIntensity();

        Set<String> states = new LinkedHashSet<>();
        states.add(primary.toUpperCase());
        if (emotion.getSecondaryEmotions() != null) states.addAll(upper(emotion.getSecondaryEmotions()));
        if (emotion.getExpandedEmotions() != null) states.addAll(upper(emotion.getExpandedEmotions()));

        boolean acuteHighIntensity = intensity >= HIGH_INTENSITY_THRESHOLD
                && states.stream().anyMatch(ACUTE_DISTRESS_STATES::contains);

        boolean useDistribution = emotionDistribution != null && !emotionDistribution.isEmpty();
        Map<String, Double> weights = useDistribution ? weightsFor(primary, states, emotionDistribution) : Map.of();
        List<String> ranked;
        if (useDistribution) {
            ranked = acuteHighIntensity
                    ? RagaRasaCatalog.ragasForWeightedEmotionsExcludingCaution(weights)
                    : RagaRasaCatalog.ragasForWeightedEmotions(weights);
        } else {
            ranked = acuteHighIntensity
                    ? RagaRasaCatalog.ragasForEmotionsExcludingCaution(states)
                    : RagaRasaCatalog.ragasForEmotions(states);
        }

        if (ranked.isEmpty()) {
            ranked = List.of(DEFAULT_FALLBACK_RAGA);
            warnings.add("No traditional raga association was found for the detected emotional state — "
                    + "defaulted to a broadly calming raga (" + DEFAULT_FALLBACK_RAGA + ").");
        }

        // Prefer the highest-ranked raga that actually has catalogued tracks (Hindi-language
        // tracks only, when restrictToHindi — e.g. the conversation pipeline's mandatory mode).
        String chosen = null;
        int tracksAvailable = 0;
        for (String candidate : ranked) {
            int count = restrictToHindi
                    ? trackRepository.findByRagaInAndLanguageIgnoreCaseOrderByPopularityDesc(List.of(candidate), "Hindi").size()
                    : trackRepository.findByRagaInOrderByPopularityDesc(List.of(candidate)).size();
            if (count > 0) {
                chosen = candidate;
                tracksAvailable = count;
                break;
            }
        }
        if (chosen != null && !chosen.equals(ranked.get(0))) {
            String scope = restrictToHindi ? "Hindi-language tracks" : "tracks";
            warnings.add("The closest emotional match, Raga " + ranked.get(0) + ", has no " + scope
                    + " in the catalogue yet — Raga " + chosen + ", the closest match that does, was used instead.");
        }
        if (chosen == null) {
            chosen = ranked.get(0);
            String scope = restrictToHindi ? "Hindi-language catalogued tracks" : "catalogued tracks";
            warnings.add("No " + scope + " were found for '" + chosen + "' or its close alternatives — "
                    + "recommendations will fall back to emotion-based matches outside this raga.");
        }

        String finalChosen = chosen;
        Optional<RagaProfile> profile = RagaRasaCatalog.profileFor(finalChosen);
        String rasa = profile.map(RagaProfile::rasa).orElse("N/A");
        String timeOfDay = profile.map(RagaProfile::timeOfDay).orElse("Any");
        String description = profile.map(RagaProfile::description).orElse("");

        String reason = buildReason(primary, intensity, finalChosen, rasa, description, acuteHighIntensity);
        if (useDistribution) reason += " " + coverageSentence(finalChosen, emotionDistribution);

        List<String> alternates = ranked.stream().filter(r -> !r.equals(finalChosen)).limit(5).toList();

        return RagaSelectionDTO.builder()
                .raga(finalChosen)
                .rasa(rasa)
                .timeOfDay(timeOfDay)
                .description(description)
                .detectedState(primary)
                .intensity(intensity)
                .reason(reason)
                .alternateRagas(alternates)
                .tracksAvailable(tracksAvailable)
                .warnings(warnings)
                .build();
    }

    /** One raga per emotion share (emotion → raga, in distribution order) plus warnings for emotions left without one. */
    public record RagaPerEmotion(Map<String, String> ragaByEmotion, List<String> warnings) {}

    /**
     * Picks a raga for EACH emotion in the patient's distribution ("multiple emotions, multiple
     * raagas"): the first raga RagaRasaCatalog associates with that emotion that has catalogued
     * tracks (Hindi tracks when restrictToHindi). For acute-distress emotions an intensifying raga
     * (e.g. Malkauns) is only used when no calming raga has tracks. Emotions with no such raga are
     * left out and named in the warnings.
     */
    public RagaPerEmotion ragasPerEmotion(Map<String, Double> emotionDistribution, boolean restrictToHindi) {
        Map<String, String> byEmotion = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (emotionDistribution == null) return new RagaPerEmotion(byEmotion, warnings);

        for (String emotion : emotionDistribution.keySet()) {
            String e = emotion.toUpperCase();
            List<String> candidates = ACUTE_DISTRESS_STATES.contains(e)
                    ? RagaRasaCatalog.ragasForEmotionsExcludingCaution(List.of(e))
                    : RagaRasaCatalog.ragasForEmotions(List.of(e));
            Optional<String> chosen = firstWithTracks(candidates, restrictToHindi);
            if (chosen.isEmpty() && ACUTE_DISTRESS_STATES.contains(e)) {
                chosen = firstWithTracks(RagaRasaCatalog.ragasForEmotions(List.of(e)), restrictToHindi);
            }
            if (chosen.isPresent()) {
                byEmotion.put(e, chosen.get());
            } else {
                warnings.add(e + " has no traditionally associated raga with " + (restrictToHindi ? "Hindi " : "")
                        + "tracks in the catalogue yet — its share of the playlist is matched by emotion only.");
            }
        }
        return new RagaPerEmotion(byEmotion, warnings);
    }

    private Optional<String> firstWithTracks(List<String> ragas, boolean restrictToHindi) {
        return ragas.stream().filter(r -> tracksIn(r, restrictToHindi) > 0).findFirst();
    }

    private int tracksIn(String raga, boolean restrictToHindi) {
        return restrictToHindi
                ? trackRepository.findByRagaInAndLanguageIgnoreCaseOrderByPopularityDesc(List.of(raga), "Hindi").size()
                : trackRepository.findByRagaInOrderByPopularityDesc(List.of(raga)).size();
    }

    private String buildReason(String primary, double intensity, String raga, String rasa,
                                String description, boolean acuteHighIntensity) {
        String intensityPct = Math.round(intensity * 100) + "%";
        StringBuilder sb = new StringBuilder("Based on the conversation's ")
                .append(primary.toLowerCase()).append("-related emotional cues (intensity ")
                .append(intensityPct).append("), Raga ").append(raga)
                .append(" was selected — traditionally associated with ").append(rasa).append(". ")
                .append(description);
        if (acuteHighIntensity) {
            sb.append(" A calming raga was prioritized because the detected intensity was high.");
        }
        return sb.toString();
    }

    /**
     * The response-level distribution (shares summing to 1.0) is the main evidence; the overall label
     * read from the whole patient text only adds a bonus, so it cannot outvote what most of the
     * patient's responses actually expressed (seen live: overall STRESS vs 43% NOSTALGIA responses).
     */
    static final double OVERALL_PRIMARY_WEIGHT = 0.25;
    static final double RELATED_EMOTION_WEIGHT = 0.05;

    private Map<String, Double> weightsFor(String primary, Set<String> related, Map<String, Double> distribution) {
        Map<String, Double> weights = new LinkedHashMap<>();
        distribution.forEach((e, pct) -> weights.merge(e.toUpperCase(), pct / 100.0, Double::sum));
        weights.merge(primary.toUpperCase(), OVERALL_PRIMARY_WEIGHT, Double::sum);
        for (String e : related) {
            if (!e.equalsIgnoreCase(primary)) weights.merge(e, RELATED_EMOTION_WEIGHT, Double::sum);
        }
        return weights;
    }

    /** e.g. "This raga's traditional emotions cover 75% of the patient's responses (SADNESS 50%, NOSTALGIA 25%)." */
    private String coverageSentence(String raga, Map<String, Double> distribution) {
        List<String> covered = new ArrayList<>();
        double share = 0;
        for (Map.Entry<String, Double> e : distribution.entrySet()) {
            if (RagaRasaCatalog.ragaMatchesEmotion(raga, e.getKey())) {
                share += e.getValue();
                covered.add(e.getKey() + " " + Math.round(e.getValue()) + "%");
            }
        }
        return "This raga's traditional emotions cover " + Math.round(share) + "% of the patient's responses"
                + (covered.isEmpty() ? "." : " (" + String.join(", ", covered) + ").");
    }

    private Set<String> upper(List<String> list) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : list) {
            if (s != null) out.add(s.toUpperCase());
        }
        return out;
    }
}
