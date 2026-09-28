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
}
