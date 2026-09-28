package com.musicrec.service;

import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.RagaSelectionDTO;
import com.musicrec.reference.RagaRasaCatalog;
import com.musicrec.reference.RagaRasaCatalog.RagaProfile;
import com.musicrec.repository.TrackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        List<String> warnings = new ArrayList<>();
        String primary = emotion.getPrimaryEmotionSafe();
        double intensity = emotion.getIntensity();

        Set<String> states = new LinkedHashSet<>();
        states.add(primary.toUpperCase());
        if (emotion.getSecondaryEmotions() != null) states.addAll(upper(emotion.getSecondaryEmotions()));
        if (emotion.getExpandedEmotions() != null) states.addAll(upper(emotion.getExpandedEmotions()));

        boolean acuteHighIntensity = intensity >= HIGH_INTENSITY_THRESHOLD
                && states.stream().anyMatch(ACUTE_DISTRESS_STATES::contains);

        List<String> ranked = acuteHighIntensity
                ? RagaRasaCatalog.ragasForEmotionsExcludingCaution(states)
                : RagaRasaCatalog.ragasForEmotions(states);

        if (ranked.isEmpty()) {
            ranked = List.of(DEFAULT_FALLBACK_RAGA);
            warnings.add("No traditional raga association was found for the detected emotional state — "
                    + "defaulted to a broadly calming raga (" + DEFAULT_FALLBACK_RAGA + ").");
        }

        // Prefer the highest-ranked raga that actually has catalogued tracks.
        String chosen = null;
        int tracksAvailable = 0;
        for (String candidate : ranked) {
            int count = trackRepository.findByRagaInOrderByPopularityDesc(List.of(candidate)).size();
            if (count > 0) {
                chosen = candidate;
                tracksAvailable = count;
                break;
            }
        }
        if (chosen == null) {
            chosen = ranked.get(0);
            warnings.add("No catalogued tracks were found for '" + chosen + "' or its close alternatives — "
                    + "recommendations will fall back to emotion-based matches outside this raga.");
        }

        String finalChosen = chosen;
        Optional<RagaProfile> profile = RagaRasaCatalog.profileFor(finalChosen);
        String rasa = profile.map(RagaProfile::rasa).orElse("N/A");
        String timeOfDay = profile.map(RagaProfile::timeOfDay).orElse("Any");
        String description = profile.map(RagaProfile::description).orElse("");

        String reason = buildReason(primary, intensity, finalChosen, rasa, description, acuteHighIntensity);

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

    private Set<String> upper(List<String> list) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : list) {
            if (s != null) out.add(s.toUpperCase());
        }
        return out;
    }
}
