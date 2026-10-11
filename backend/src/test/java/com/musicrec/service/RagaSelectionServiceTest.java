package com.musicrec.service;

import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.RagaSelectionDTO;
import com.musicrec.entity.Track;
import com.musicrec.repository.TrackRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagaSelectionServiceTest {

    private EmotionMetadata emotionOf(String primary, double intensity) {
        EmotionMetadata e = new EmotionMetadata();
        e.setPrimaryEmotion(primary);
        e.setIntensity(intensity);
        e.setSecondaryEmotions(List.of());
        e.setExpandedEmotions(List.of());
        e.setLanguage("English");
        return e;
    }

    private Track trackWithId(long id) {
        Track t = new Track();
        t.setId(id);
        return t;
    }

    @Test
    void highIntensityAcuteDistressAvoidsCautionRagas() {
        TrackRepository repo = mock(TrackRepository.class);
        // Give every raga at least one track so selection doesn't fall through to the default fallback.
        when(repo.findByRagaInOrderByPopularityDesc(anyList())).thenReturn(List.of(trackWithId(1L)));

        RagaSelectionService service = new RagaSelectionService(repo);
        RagaSelectionDTO selection = service.selectRaga(emotionOf("STRESS", 0.85));

        // Malkauns is flagged cautionAtHighIntensity and is otherwise a strong STRESS match —
        // it must not be chosen (nor appear ahead of a safer pick) at this intensity.
        assertThat(selection.getRaga()).isNotEqualTo("Malkauns");
        assertThat(selection.getReason()).contains("STRESS".toLowerCase());
        assertThat(selection.getWarnings()).isEmpty();
    }

    @Test
    void fallsBackToDefaultRagaWhenNoCatalogueTracksExistForAnyCandidate() {
        TrackRepository repo = mock(TrackRepository.class);
        when(repo.findByRagaInOrderByPopularityDesc(anyList())).thenReturn(List.of());

        RagaSelectionService service = new RagaSelectionService(repo);
        RagaSelectionDTO selection = service.selectRaga(emotionOf("JOY", 0.6));

        assertThat(selection.getRaga()).isNotBlank();
        assertThat(selection.getTracksAvailable()).isZero();
        assertThat(selection.getWarnings()).isNotEmpty();
    }

    @Test
    void unknownEmotionFallsBackToDefaultCalmingRaga() {
        TrackRepository repo = mock(TrackRepository.class);
        when(repo.findByRagaInOrderByPopularityDesc(anyList())).thenReturn(List.of(trackWithId(1L)));

        RagaSelectionService service = new RagaSelectionService(repo);
        RagaSelectionDTO selection = service.selectRaga(emotionOf("SOME_UNMAPPED_STATE", 0.5));

        assertThat(selection.getRaga()).isEqualTo("Yaman");
        assertThat(selection.getWarnings()).anyMatch(w -> w.contains("No traditional raga association"));
    }

    private RagaSelectionService serviceWithHindiTracksForEveryRaga() {
        TrackRepository repo = mock(TrackRepository.class);
        when(repo.findByRagaInAndLanguageIgnoreCaseOrderByPopularityDesc(anyList(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(trackWithId(1L)));
        return new RagaSelectionService(repo);
    }

    @Test
    void catalogRanksRagasByHowMuchOfTheEmotionDistributionTheyCover() {
        java.util.Map<String, Double> weights = java.util.Map.of("ANXIETY", 0.5, "SADNESS", 0.25, "NOSTALGIA", 0.25);

        List<String> ranked = com.musicrec.reference.RagaRasaCatalog.ragasForWeightedEmotions(weights);

        assertThat(ranked).isNotEmpty();
        for (int i = 1; i < ranked.size(); i++) {
            assertThat(com.musicrec.reference.RagaRasaCatalog.coverage(ranked.get(i - 1), weights))
                    .isGreaterThanOrEqualTo(com.musicrec.reference.RagaRasaCatalog.coverage(ranked.get(i), weights));
        }
    }

    @Test
    void emotionDistributionChangesTheSelectedRaga() {
        RagaSelectionService service = serviceWithHindiTracksForEveryRaga();
        EmotionMetadata overall = emotionOf("SADNESS", 0.5);

        RagaSelectionDTO mostlyJoy = service.selectRaga(overall, java.util.Map.of("JOY", 100.0), true);
        RagaSelectionDTO mostlySad = service.selectRaga(overall, java.util.Map.of("SADNESS", 100.0), true);

        assertThat(mostlyJoy.getRaga()).isNotEqualTo(mostlySad.getRaga());
    }

    @Test
    void reasonStatesWhichShareOfThePatientsResponsesTheRagaCovers() {
        RagaSelectionDTO selection = serviceWithHindiTracksForEveryRaga().selectRaga(
                emotionOf("SADNESS", 0.5),
                new java.util.LinkedHashMap<>(java.util.Map.of("SADNESS", 50.0, "ANXIETY", 25.0, "NOSTALGIA", 25.0)), true);

        assertThat(selection.getReason()).contains("% of the patient's responses");
    }

    @Test
    void distributionAwareSelectionStillAvoidsCautionRagasAtHighIntensity() {
        RagaSelectionDTO selection = serviceWithHindiTracksForEveryRaga()
                .selectRaga(emotionOf("STRESS", 0.9), java.util.Map.of("STRESS", 100.0), true);

        assertThat(selection.getRaga()).isNotEqualTo("Malkauns");
    }

    @Test
    void dominantResponseEmotionOutweighsADisagreeingOverallLabel() {
        // Seen live (R01): overall label STRESS, but 43% of the patient's responses were NOSTALGIA.
        EmotionMetadata overall = emotionOf("STRESS", 0.6);
        overall.setSecondaryEmotions(List.of("ANXIETY", "SADNESS"));
        overall.setExpandedEmotions(List.of("ANXIETY", "FRUSTRATION", "MELANCHOLY", "NOSTALGIA", "CALM"));
        java.util.Map<String, Double> distribution = new java.util.LinkedHashMap<>();
        distribution.put("NOSTALGIA", 42.8);
        distribution.put("SADNESS", 28.6);
        distribution.put("STRESS", 14.3);
        distribution.put("RELAXATION", 14.3);

        RagaSelectionDTO selection = serviceWithHindiTracksForEveryRaga().selectRaga(overall, distribution, true);

        assertThat(com.musicrec.reference.RagaRasaCatalog.ragaMatchesEmotion(selection.getRaga(), "NOSTALGIA"))
                .as("chosen raga %s should cover the dominant emotion", selection.getRaga()).isTrue();
    }

    @Test
    void whenTheBestMatchingRagaHasNoHindiTracksTheSubstitutionIsDisclosed() {
        TrackRepository repo = mock(TrackRepository.class);
        when(repo.findByRagaInAndLanguageIgnoreCaseOrderByPopularityDesc(org.mockito.ArgumentMatchers.eq(List.of("Yaman")),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(trackWithId(1L)));
        java.util.Map<String, Double> distribution = new java.util.LinkedHashMap<>();
        distribution.put("STRESS", 60.0);
        distribution.put("CALM", 40.0);

        RagaSelectionDTO selection = new RagaSelectionService(repo).selectRaga(emotionOf("STRESS", 0.5), distribution, true);

        assertThat(selection.getRaga()).isEqualTo("Yaman");
        assertThat(selection.getWarnings()).anySatisfy(w -> assertThat(w).contains("Bhairav").containsIgnoringCase("Hindi"));
    }
}
