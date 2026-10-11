package com.musicrec.evaluation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A labelled set of Health Consultant / Patient conversations (evaluation/dataset/*.json).
 *
 * Every PATIENT turn carries the emotion it was written to express; the expected emotion
 * distribution and dominant emotion are derived from those labels (EvaluationScoring), so
 * there is a single source of truth per case. expectedDominantEmotion only needs to be set
 * to break a tie. Optional human labels, each replacing an automatic proxy when present:
 *   expectedRagas  — acceptable ragas (e.g. from a musicologist) instead of the catalogue-derived set
 *   expectedGenres — acceptable genres; genre accuracy is only reported for cases that have them
 *   songJudgments  — song title → graded relevance (0–3) from a human judge; replaces the
 *                    metadata-derived relevance for that case (unjudged songs count as 0)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationDataset(String name, String description, List<Case> cases) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Case(String id, String title, List<Turn> turns,
                       String expectedDominantEmotion, List<String> expectedRagas,
                       List<String> expectedGenres, java.util.Map<String, Integer> songJudgments) {}

    /** @param emotion ground-truth label for a PATIENT turn; ignored for consultant turns */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Turn(String role, String text, String emotion) {}
}
