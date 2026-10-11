package com.musicrec.evaluation;

import java.util.List;
import java.util.Map;

/**
 * Standard ranking/retrieval metrics — Precision@K, Recall@K, NDCG@K — req.
 * #6C: Top-10 song recommendation evaluation.
 *
 * Takes a ranked list of item ids plus a caller-supplied relevance map; it
 * does not invent relevance judgments. Recall@K and NDCG@K require knowing
 * the *complete* relevant set (or graded relevance) for a query, which no
 * labeled relevance dataset currently exists for in this project — see
 * README's Evaluation section. Precision@K only needs per-item relevance for
 * the K items actually returned, so it can be computed as soon as even a
 * small hand-labeled relevance judgment exists for a given query.
 */
public final class RankingMetrics {

    private RankingMetrics() {}

    /**
     * @param rankedIds   item ids in ranked order (highest-ranked first)
     * @param relevance   item id → relevance judgment (0 = not relevant, >0 = relevant/graded)
     * @param k           cutoff
     */
    public static double precisionAtK(List<Long> rankedIds, Map<Long, Integer> relevance, int k) {
        List<Long> top = rankedIds.stream().limit(k).toList();
        if (top.isEmpty()) return 0.0;
        long relevantCount = top.stream().filter(id -> relevance.getOrDefault(id, 0) > 0).count();
        return round((double) relevantCount / top.size());
    }

    /** Requires the TOTAL number of relevant items for the query (not just those relevant within top-K). */
    public static double recallAtK(List<Long> rankedIds, Map<Long, Integer> relevance, int k, int totalRelevantForQuery) {
        if (totalRelevantForQuery <= 0) return 0.0;
        List<Long> top = rankedIds.stream().limit(k).toList();
        long relevantCount = top.stream().filter(id -> relevance.getOrDefault(id, 0) > 0).count();
        return round((double) relevantCount / totalRelevantForQuery);
    }

    /** Normalized Discounted Cumulative Gain @K, using graded relevance values as gains. */
    public static double ndcgAtK(List<Long> rankedIds, Map<Long, Integer> relevance, int k) {
        List<Long> top = rankedIds.stream().limit(k).toList();
        if (top.isEmpty()) return 0.0;

        double dcg = 0.0;
        for (int i = 0; i < top.size(); i++) {
            int gain = relevance.getOrDefault(top.get(i), 0);
            dcg += gain / log2(i + 2); // rank is 1-indexed in the formula → i+2
        }

        List<Integer> idealGains = relevance.values().stream()
                .sorted((a, b) -> b - a)
                .limit(k)
                .toList();
        double idealDcg = 0.0;
        for (int i = 0; i < idealGains.size(); i++) {
            idealDcg += idealGains.get(i) / log2(i + 2);
        }

        return idealDcg == 0.0 ? 0.0 : round(dcg / idealDcg);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
