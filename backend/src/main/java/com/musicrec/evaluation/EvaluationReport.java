package com.musicrec.evaluation;

import java.util.List;
import java.util.Map;

/**
 * Result of one evaluation run over a labelled conversation dataset (EvaluationService).
 * Aggregates cover completed cases only; failed cases are listed with their error.
 */
public record EvaluationReport(
        String dataset,
        String runAt,
        String llmModel,
        int k,
        int casesTotal,
        int casesCompleted,
        int casesFailed,
        /** Cases where any LLM step fell back (keyword emotion analysis or rank-order song selection). */
        int casesWithLlmFallback,
        EmotionLevel emotion,
        RagaLevel raga,
        SongLevel songs,
        double endToEndAccuracy,
        Map<String, EmotionBreakdown> byExpectedDominantEmotion,
        List<CaseResult> cases,
        List<String> notes) {

    /**
     * @param segmentAccuracy           Patient responses whose detected emotion equals the label (micro-average)
     * @param dominantAccuracy          cases whose detected dominant emotion equals the expected one
     * @param meanDistributionSimilarity mean of 1 − total variation distance between expected and detected distributions
     */
    public record EmotionLevel(double segmentAccuracy, int segmentsCorrect, int segmentsTotal,
                               double dominantAccuracy, double meanDistributionSimilarity) {}

    /**
     * @param top1Accuracy              selected raga is in the case's acceptable set
     * @param top3Accuracy              selected raga or one of the next two ranked alternates is
     * @param top1AccuracyReachable     Top-1 over cases where at least one acceptable raga has Hindi tracks
     * @param catalogueAgreementRate    selected raga is catalogued for at least one DETECTED emotion
     *                                  (internal consistency, not correctness)
     */
    public record RagaLevel(double top1Accuracy, double top3Accuracy,
                            double top1AccuracyReachable, int reachableCases,
                            double catalogueAgreementRate) {}

    /**
     * @param meanEmotionShareFidelity  mean similarity between the expected emotion distribution and the
     *                                  playlist's emotion composition (1 − total variation distance)
     */
    /**
     * @param meanSongEmotionAccuracy share of the Top-K whose primary emotion is expected
     * @param meanSongRagaAccuracy    share of the Top-K set in an acceptable raga (untagged songs are misses)
     * @param meanGenreAccuracy       null when no case has genre labels
     */
    public record SongLevel(double meanPrecisionAtK, double meanRecallAtK, double meanNdcgAtK,
                            double meanEmotionShareFidelity,
                            double meanSongEmotionAccuracy,
                            double meanSongRagaAccuracy,
                            double meanRagaTaggedShare,
                            Double meanGenreAccuracy,
                            int genreLabelledCases,
                            int humanJudgedCases) {}

    public record EmotionBreakdown(int cases, double dominantAccuracy, double ragaTop1Accuracy, double meanNdcgAtK) {}

    public record CaseResult(
            String id,
            String title,
            String error,
            String expectedDominant,
            String detectedDominant,
            boolean dominantCorrect,
            List<String> segmentLabels,
            List<String> segmentPredictions,
            int segmentsCorrect,
            int segmentsTotal,
            Map<String, Double> expectedDistribution,
            Map<String, Double> detectedDistribution,
            double distributionSimilarity,
            String selectedRaga,
            List<String> rankedRagas,
            List<String> acceptableRagas,
            String ragaLabelSource,
            boolean ragaReachable,
            boolean ragaTop1Hit,
            boolean ragaTop3Hit,
            List<Long> recommendedTrackIds,
            List<String> recommendedTitles,
            double precisionAtK,
            double recallAtK,
            double ndcgAtK,
            Map<String, Double> playlistComposition,
            double emotionShareFidelity,
            double songEmotionAccuracy,
            double songRagaAccuracy,
            double ragaTaggedShare,
            Double genreAccuracy,
            String relevanceSource,
            int keywordFallbackSegments,
            boolean llmSongSelectionUsed,
            int attempts,
            List<String> warnings) {

        static CaseResult failed(String id, String title, String error) {
            return new CaseResult(id, title, error, null, null, false, List.of(), List.of(), 0, 0,
                    Map.of(), Map.of(), 0, null, List.of(), List.of(), null, false, false, false,
                    List.of(), List.of(), 0, 0, 0, Map.of(), 0, 0, 0, 0, null, null, 0, false, 0, List.of());
        }

        boolean completed() {
            return error == null;
        }

        boolean usedLlmFallback() {
            return keywordFallbackSegments > 0 || !llmSongSelectionUsed;
        }
    }
}
