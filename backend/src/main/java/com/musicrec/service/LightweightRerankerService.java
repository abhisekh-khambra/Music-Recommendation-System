package com.musicrec.service;

import com.musicrec.dto.EvidenceRecord;
import com.musicrec.entity.Track;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Cheap, code-only reranking stage that sits between FUSE and SELECT.
 *
 * Trims the fused candidate pool down to a smaller, higher-quality shortlist
 * before it reaches the Groq LLM call — a distinct stage rather than folding
 * ranking and explanation into one model call. Composite score is a blend of
 * each track's (weighted) RRF score and its evidence strength, both
 * normalized to [0,1] so neither signal dominates by raw magnitude.
 */
@Service
@Slf4j
public class LightweightRerankerService {

    @Value("${app.recommendation.rerank-pool-size:15}")
    private int rerankPoolSize;

    private static final double RRF_WEIGHT      = 0.5;
    private static final double EVIDENCE_WEIGHT = 0.5;

    public List<Track> rerank(List<Track> candidates,
                               Map<Long, Double> rrfScores,
                               Map<Long, List<EvidenceRecord>> evidenceByTrack) {
        if (candidates.size() <= rerankPoolSize) {
            return candidates;
        }

        double maxRrf = rrfScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        double maxEvidence = candidates.stream()
                .mapToDouble(t -> evidenceStrengthSum(t.getId(), evidenceByTrack))
                .max().orElse(1.0);

        List<Track> reranked = candidates.stream()
                .sorted(Comparator.comparingDouble(
                        (Track t) -> compositeScore(t, rrfScores, evidenceByTrack, maxRrf, maxEvidence)).reversed())
                .limit(rerankPoolSize)
                .toList();

        log.info("LIGHTWEIGHT_RERANK: {} -> {} candidates (pool cap={})",
                candidates.size(), reranked.size(), rerankPoolSize);

        return reranked;
    }

    private double compositeScore(Track t,
                                   Map<Long, Double> rrfScores,
                                   Map<Long, List<EvidenceRecord>> evidenceByTrack,
                                   double maxRrf, double maxEvidence) {
        double rrfNorm      = rrfScores.getOrDefault(t.getId(), 0.0) / Math.max(maxRrf, 1e-9);
        double evidenceNorm = evidenceStrengthSum(t.getId(), evidenceByTrack) / Math.max(maxEvidence, 1e-9);
        return RRF_WEIGHT * rrfNorm + EVIDENCE_WEIGHT * evidenceNorm;
    }

    private double evidenceStrengthSum(Long trackId, Map<Long, List<EvidenceRecord>> evidenceByTrack) {
        return evidenceByTrack.getOrDefault(trackId, List.of()).stream()
                .mapToDouble(EvidenceRecord::strength)
                .sum();
    }
}
