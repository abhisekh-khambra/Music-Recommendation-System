package com.musicrec.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RagaMappingEvaluatorTest {

    @Test
    void ragaConsistentWithCatalogueCountsAsAgreement() {
        // Bhairavi is catalogued for SADNESS in RagaRasaCatalog
        var selections = List.of(new RagaMappingEvaluator.Selection("Bhairavi", List.of("SADNESS")));

        RagaMappingEvaluator.Report report = RagaMappingEvaluator.evaluate(selections);

        assertThat(report.agreementRate()).isEqualTo(1.0);
        assertThat(report.cases().get(0).consistentWithCatalogue()).isTrue();
    }

    @Test
    void ragaNotCataloguedForEmotionCountsAsDisagreement() {
        // Hindol is catalogued for JOY/EXCITEMENT, not SADNESS
        var selections = List.of(new RagaMappingEvaluator.Selection("Hindol", List.of("SADNESS")));

        RagaMappingEvaluator.Report report = RagaMappingEvaluator.evaluate(selections);

        assertThat(report.agreementRate()).isZero();
        assertThat(report.cases().get(0).consistentWithCatalogue()).isFalse();
    }

    @Test
    void mixedCasesProduceAPartialAgreementRate() {
        var selections = List.of(
                new RagaMappingEvaluator.Selection("Bhairavi", List.of("SADNESS")),   // consistent
                new RagaMappingEvaluator.Selection("Hindol", List.of("SADNESS")));     // not consistent

        RagaMappingEvaluator.Report report = RagaMappingEvaluator.evaluate(selections);

        assertThat(report.agreementRate()).isEqualTo(0.5);
    }

    @Test
    void top1AccuracyComparesSelectedRagaAgainstExpectedGroundTruth() {
        List<String> selected = List.of("Bhairavi", "Yaman", "Hindol");
        List<String> expected = List.of("Bhairavi", "Malkauns", "Hindol");

        assertThat(RagaMappingEvaluator.top1Accuracy(selected, expected)).isEqualTo(2.0 / 3.0, within(0.001));
    }

    @Test
    void topKAccuracyCreditsAMatchAnywhereInTheRankedListWithinK() {
        List<java.util.List<String>> ranked = List.of(
                List.of("Yaman", "Bhairavi", "Kafi"),   // expected "Bhairavi" is rank 2 — within top 3
                List.of("Hindol", "Adana"));             // expected "Malkauns" never appears

        List<String> expected = List.of("Bhairavi", "Malkauns");

        assertThat(RagaMappingEvaluator.topKAccuracy(ranked, expected, 3)).isEqualTo(0.5);
        // top-1 only credits an exact rank-1 match, so "Bhairavi" at rank 2 doesn't count
        assertThat(RagaMappingEvaluator.topKAccuracy(ranked, expected, 1)).isEqualTo(0.0);
    }

    @Test
    void topKAnyOfCreditsAnyAcceptableRagaWithinK() {
        List<List<String>> ranked = List.of(
                List.of("Yaman", "Bhairavi"),     // Yaman is acceptable → top-1 hit
                List.of("Bhairav", "Darbari Kanada"), // acceptable Darbari Kanada at rank 2 → top-2 hit only
                List.of("Hindol"));               // nothing acceptable
        List<Set<String>> acceptable = List.of(
                Set.of("yaman", "Desh"),
                Set.of("Darbari Kanada", "Bhairavi"),
                Set.of());

        assertThat(RagaMappingEvaluator.topKAccuracyAnyOf(ranked, acceptable, 1)).isEqualTo(0.3333);
        assertThat(RagaMappingEvaluator.topKAccuracyAnyOf(ranked, acceptable, 2)).isEqualTo(0.6667);
    }

    @Test
    void mismatchedLengthsThrowForTopKAndTop1() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> RagaMappingEvaluator.top1Accuracy(List.of("A"), List.of("A", "B"))))
                .hasMessageContaining("same length");
    }
}
