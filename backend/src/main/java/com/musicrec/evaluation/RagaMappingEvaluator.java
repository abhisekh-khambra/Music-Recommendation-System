package com.musicrec.evaluation;

import com.musicrec.reference.RagaRasaCatalog;

import java.util.Collection;
import java.util.List;

/**
 * Evaluates whether a selected Raga is consistent with the project's own
 * documented reference mapping (RagaRasaCatalog) — req. #6B.
 *
 * Important scope limitation, stated rather than glossed over: RagaRasaCatalog
 * IS the reference mapping actually used by RagaSelectionService, so this
 * checks internal consistency (did the selector follow its own documented
 * rules?), not whether that mapping is musicologically/clinically correct
 * against an independent, expert-validated source. No such independent
 * reference or expert-annotated dataset is wired into this project — see
 * README's Evaluation section. Treat "agreement" below as "traceable to the
 * documented rasa-theory table", not "proven correct."
 */
public final class RagaMappingEvaluator {

    private RagaMappingEvaluator() {}

    public record CaseResult(String raga, Collection<String> detectedEmotions, boolean consistentWithCatalogue) {}

    public record Report(List<CaseResult> cases, double agreementRate) {}

    /**
     * @param selections pairs of (raga chosen, emotions detected) to check against RagaRasaCatalog
     */
    public static Report evaluate(List<Selection> selections) {
        List<CaseResult> results = selections.stream()
                .map(s -> new CaseResult(
                        s.raga(),
                        s.detectedEmotions(),
                        s.detectedEmotions().stream().anyMatch(e -> RagaRasaCatalog.ragaMatchesEmotion(s.raga(), e))))
                .toList();

        double agreementRate = results.isEmpty() ? 0.0
                : results.stream().mapToDouble(r -> r.consistentWithCatalogue() ? 1.0 : 0.0).average().orElse(0.0);

        return new Report(results, Math.round(agreementRate * 10000.0) / 10000.0);
    }

    public record Selection(String raga, Collection<String> detectedEmotions) {}

    /**
     * Top-1 accuracy: fraction of cases where the system's chosen raga equals the
     * expected (ground-truth) raga for that case — req. #9's "Top-1 accuracy" for
     * Raga recommendation. Requires real expected-raga labels; invent none here.
     */
    public static double top1Accuracy(List<String> selectedRagas, List<String> expectedRagas) {
        if (selectedRagas.size() != expectedRagas.size()) {
            throw new IllegalArgumentException("selectedRagas and expectedRagas must be the same length");
        }
        if (selectedRagas.isEmpty()) return 0.0;
        long correct = 0;
        for (int i = 0; i < selectedRagas.size(); i++) {
            if (selectedRagas.get(i).equalsIgnoreCase(expectedRagas.get(i))) correct++;
        }
        return Math.round((double) correct / selectedRagas.size() * 10000.0) / 10000.0;
    }

    /**
     * Top-k accuracy when each case has a SET of acceptable ragas (several ragas are traditionally
     * appropriate for one emotional state): a case counts as correct if any of the system's first k
     * ranked ragas is in that case's acceptable set. k=1 gives Top-1 accuracy. Cases with an empty
     * acceptable set count as incorrect — no raga could be right for them.
     */
    public static double topKAccuracyAnyOf(List<List<String>> rankedRagaLists,
                                           List<? extends Collection<String>> acceptableRagas, int k) {
        if (rankedRagaLists.size() != acceptableRagas.size()) {
            throw new IllegalArgumentException("rankedRagaLists and acceptableRagas must be the same length");
        }
        if (rankedRagaLists.isEmpty()) return 0.0;
        long correct = 0;
        for (int i = 0; i < rankedRagaLists.size(); i++) {
            if (hitAtK(rankedRagaLists.get(i), acceptableRagas.get(i), k)) correct++;
        }
        return Math.round((double) correct / rankedRagaLists.size() * 10000.0) / 10000.0;
    }

    /** True if any of the first k ranked ragas is in the acceptable set (case-insensitive). */
    public static boolean hitAtK(List<String> rankedRagas, Collection<String> acceptable, int k) {
        return rankedRagas.stream().limit(k)
                .anyMatch(r -> acceptable.stream().anyMatch(a -> a.equalsIgnoreCase(r)));
    }

    /**
     * Top-k accuracy: fraction of cases where the expected raga appears anywhere in the
     * system's ranked candidate list (e.g. RagaSelectionDTO.alternateRagas prefixed with
     * the chosen raga) within the first k entries.
     */
    public static double topKAccuracy(List<List<String>> rankedRagaLists, List<String> expectedRagas, int k) {
        if (rankedRagaLists.size() != expectedRagas.size()) {
            throw new IllegalArgumentException("rankedRagaLists and expectedRagas must be the same length");
        }
        if (rankedRagaLists.isEmpty()) return 0.0;
        long correct = 0;
        for (int i = 0; i < rankedRagaLists.size(); i++) {
            List<String> topK = rankedRagaLists.get(i).stream().limit(k).toList();
            String expected = expectedRagas.get(i);
            if (topK.stream().anyMatch(r -> r.equalsIgnoreCase(expected))) correct++;
        }
        return Math.round((double) correct / rankedRagaLists.size() * 10000.0) / 10000.0;
    }
}
