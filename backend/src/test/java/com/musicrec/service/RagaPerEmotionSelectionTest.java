package com.musicrec.service;

import com.musicrec.entity.Track;
import com.musicrec.repository.TrackRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Meeting requirement: "multiple emotions, multiple raagas" — each emotion's share of the
 * playlist gets its own raga, not just the one overall raga.
 */
class RagaPerEmotionSelectionTest {

    /** Only these ragas have Hindi tracks in the mocked catalogue. */
    private RagaSelectionService serviceWithHindiRagas(Set<String> hindiRagas) {
        TrackRepository repo = mock(TrackRepository.class);
        when(repo.findByRagaInAndLanguageIgnoreCaseOrderByPopularityDesc(anyList(), eq("Hindi"))).thenAnswer(inv -> {
            List<String> ragas = inv.getArgument(0);
            Track t = new Track();
            t.setId(1L);
            return ragas.stream().anyMatch(hindiRagas::contains) ? List.of(t) : List.of();
        });
        return new RagaSelectionService(repo);
    }

    private Map<String, Double> dist(Object... pairs) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        return m;
    }

    @Test
    void eachEmotionGetsItsBestRagaThatHasHindiTracks() {
        RagaSelectionService service = serviceWithHindiRagas(Set.of("Darbari Kanada", "Yaman"));

        RagaSelectionService.RagaPerEmotion result =
                service.ragasPerEmotion(dist("SADNESS", 50, "NOSTALGIA", 50), true);

        // SADNESS: Bhairavi is the first catalogue match but has no Hindi tracks → Darbari Kanada.
        assertThat(result.ragaByEmotion()).containsEntry("SADNESS", "Darbari Kanada");
        assertThat(result.ragaByEmotion()).containsEntry("NOSTALGIA", "Yaman");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void emotionWithNoHindiRagaIsLeftOutAndWarned() {
        RagaSelectionService service = serviceWithHindiRagas(Set.of("Darbari Kanada"));

        RagaSelectionService.RagaPerEmotion result =
                service.ragasPerEmotion(dist("SADNESS", 70, "JOY", 30), true);

        assertThat(result.ragaByEmotion()).containsOnlyKeys("SADNESS");
        assertThat(result.warnings()).anyMatch(w -> w.contains("JOY"));
    }

    @Test
    void keepsTheDistributionOrder() {
        RagaSelectionService service = serviceWithHindiRagas(Set.of("Darbari Kanada", "Yaman", "Bhairav"));

        RagaSelectionService.RagaPerEmotion result =
                service.ragasPerEmotion(dist("STRESS", 50, "SADNESS", 30, "LOVE", 20), true);

        assertThat(result.ragaByEmotion().keySet()).containsExactly("STRESS", "SADNESS", "LOVE");
        assertThat(result.ragaByEmotion()).containsEntry("STRESS", "Bhairav");
    }

    @Test
    void distressEmotionAvoidsAnIntensifyingRagaWhenACalmingOneHasHindiTracks() {
        // Malkauns is flagged cautionAtHighIntensity and is the first STRESS match in the catalogue.
        RagaSelectionService service = serviceWithHindiRagas(Set.of("Malkauns", "Bhairav"));

        assertThat(service.ragasPerEmotion(dist("STRESS", 100), true).ragaByEmotion())
                .containsEntry("STRESS", "Bhairav");
    }

    @Test
    void intensifyingRagaIsStillUsedWhenItIsTheOnlyOneWithHindiTracks() {
        RagaSelectionService service = serviceWithHindiRagas(Set.of("Malkauns"));

        assertThat(service.ragasPerEmotion(dist("ANXIETY", 100), true).ragaByEmotion())
                .containsEntry("ANXIETY", "Malkauns");
    }

    @Test
    void emptyDistributionGivesNoRagas() {
        assertThat(serviceWithHindiRagas(Set.of("Yaman")).ragasPerEmotion(Map.of(), true).ragaByEmotion()).isEmpty();
    }
}
