package com.musicrec.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies ranking-metric arithmetic against hand-computed examples — no real relevance dataset exists yet. */
class RankingMetricsTest {

    @Test
    void precisionAtKCountsRelevantItemsWithinTopK() {
        List<Long> ranked = List.of(1L, 2L, 3L, 4L, 5L);
        Map<Long, Integer> relevance = Map.of(1L, 1, 2L, 0, 3L, 1, 4L, 0, 5L, 1);

        assertThat(RankingMetrics.precisionAtK(ranked, relevance, 5)).isEqualTo(0.6); // 3/5 relevant
        assertThat(RankingMetrics.precisionAtK(ranked, relevance, 2)).isEqualTo(0.5); // items 1,2 → 1 relevant of 2
    }

    @Test
    void recallAtKNeedsTotalRelevantCount() {
        List<Long> ranked = List.of(1L, 2L, 3L);
        Map<Long, Integer> relevance = Map.of(1L, 1, 2L, 0, 3L, 1);

        // 2 relevant found in top-3, but 4 relevant exist in total for this query
        assertThat(RankingMetrics.recallAtK(ranked, relevance, 3, 4)).isEqualTo(0.5);
    }

    @Test
    void ndcgIsOneWhenRankingIsAlreadyIdeal() {
        List<Long> ranked = List.of(1L, 2L, 3L);
        Map<Long, Integer> relevance = Map.of(1L, 3, 2L, 2, 3L, 1);

        assertThat(RankingMetrics.ndcgAtK(ranked, relevance, 3)).isEqualTo(1.0);
    }

    @Test
    void ndcgPenalizesAWorseThanIdealOrdering() {
        List<Long> ranked = List.of(3L, 2L, 1L); // reversed from ideal
        Map<Long, Integer> relevance = Map.of(1L, 3, 2L, 2, 3L, 1);

        double ndcg = RankingMetrics.ndcgAtK(ranked, relevance, 3);
        assertThat(ndcg).isLessThan(1.0).isGreaterThan(0.0);
    }

    @Test
    void emptyRelevanceYieldsZeroNdcgRatherThanDivideByZero() {
        assertThat(RankingMetrics.ndcgAtK(List.of(1L), Map.of(), 5)).isZero();
    }
}
