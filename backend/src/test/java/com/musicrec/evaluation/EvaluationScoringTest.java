package com.musicrec.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.Track;
import com.musicrec.reference.EmotionLabels;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationScoringTest {

    private EvaluationDataset.Case caseWith(String dominantOverride, String... patientEmotions) {
        List<EvaluationDataset.Turn> turns = new java.util.ArrayList<>();
        for (String e : patientEmotions) {
            turns.add(new EvaluationDataset.Turn("HEALTH_CONSULTANT", "How are you?", null));
            turns.add(new EvaluationDataset.Turn("PATIENT", "...", e));
        }
        return new EvaluationDataset.Case("T", "test", turns, dominantOverride, null, null, null);
    }

    private Map<String, Double> dist(Object... pairs) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        return m;
    }

    @Test
    void expectedDistributionAndDominantComeFromPatientLabels() {
        EvaluationDataset.Case c = caseWith(null, "SADNESS", "NOSTALGIA", "SADNESS", "SADNESS");
        assertThat(EvaluationScoring.patientLabels(c)).containsExactly("SADNESS", "NOSTALGIA", "SADNESS", "SADNESS");
        assertThat(EvaluationScoring.distribution(EvaluationScoring.patientLabels(c)))
                .containsExactly(Map.entry("SADNESS", 75.0), Map.entry("NOSTALGIA", 25.0));
        assertThat(EvaluationScoring.expectedDominant(c)).isEqualTo("SADNESS");
    }

    @Test
    void tiedDominantNeedsAnExplicitLabel() {
        assertThatThrownBy(() -> EvaluationScoring.expectedDominant(caseWith(null, "JOY", "CALM")))
                .hasMessageContaining("tie");
        assertThat(EvaluationScoring.expectedDominant(caseWith("calm", "JOY", "CALM"))).isEqualTo("CALM");
    }

    @Test
    void distributionSimilarityIsOneMinusTotalVariationDistance() {
        assertThat(EvaluationScoring.distributionSimilarity(dist("SADNESS", 75, "NOSTALGIA", 25),
                dist("NOSTALGIA", 25, "SADNESS", 75))).isEqualTo(1.0);
        assertThat(EvaluationScoring.distributionSimilarity(dist("JOY", 100), dist("SADNESS", 100))).isEqualTo(0.0);
        // |0.75-0.5| + |0.25-0| + |0-0.5| = 1.0 → TVD 0.5
        assertThat(EvaluationScoring.distributionSimilarity(dist("SADNESS", 75, "NOSTALGIA", 25),
                dist("SADNESS", 50, "STRESS", 50))).isEqualTo(0.5);
    }

    @Test
    void playlistCompositionPoolsOffTargetSongsAsOther() {
        TrackDTO sad = new TrackDTO(); sad.setPrimaryEmotion("SADNESS");
        TrackDTO joy = new TrackDTO(); joy.setPrimaryEmotion("JOY");
        Map<String, Double> comp = EvaluationScoring.playlistComposition(List.of(sad, sad, sad, joy), Set.of("SADNESS"));
        assertThat(comp).containsEntry("SADNESS", 75.0).containsEntry("OTHER", 25.0);
    }

    @Test
    void relevanceIsGradedByEmotionAndRaga() {
        Set<String> expected = Set.of("SADNESS", "NOSTALGIA");
        List<String> ragas = List.of("Bhairavi", "Darbari Kanada");

        Track primaryAndRaga = new Track();
        primaryAndRaga.setPrimaryEmotion("SADNESS"); primaryAndRaga.setRaga("Darbari Kanada");
        Track primaryOnly = new Track();
        primaryOnly.setPrimaryEmotion("NOSTALGIA");
        Track secondaryOnly = new Track();
        secondaryOnly.setPrimaryEmotion("LOVE"); secondaryOnly.setSecondaryEmotions(List.of("NOSTALGIA"));
        Track ragaOnly = new Track();
        ragaOnly.setPrimaryEmotion("CALM"); ragaOnly.setRaga("bhairavi");
        Track none = new Track();
        none.setPrimaryEmotion("JOY");

        assertThat(EvaluationScoring.relevance(primaryAndRaga, expected, ragas)).isEqualTo(3);
        assertThat(EvaluationScoring.relevance(primaryOnly, expected, ragas)).isEqualTo(2);
        assertThat(EvaluationScoring.relevance(secondaryOnly, expected, ragas)).isEqualTo(1);
        assertThat(EvaluationScoring.relevance(ragaOnly, expected, ragas)).isEqualTo(1);
        assertThat(EvaluationScoring.relevance(none, expected, ragas)).isEqualTo(0);
    }

    private TrackDTO song(String emotion, String raga, String genre) {
        TrackDTO t = new TrackDTO();
        t.setPrimaryEmotion(emotion);
        t.setRaga(raga);
        t.setGenre(genre);
        return t;
    }

    @Test
    void songEmotionAccuracyIsTheShareOfSongsWithAnExpectedPrimaryEmotion() {
        List<TrackDTO> top = List.of(song("SADNESS", null, null), song("NOSTALGIA", null, null),
                song("JOY", null, null), song(null, null, null));
        assertThat(EvaluationScoring.songEmotionAccuracy(top, Set.of("SADNESS", "NOSTALGIA"))).isEqualTo(0.5);
        assertThat(EvaluationScoring.songEmotionAccuracy(List.of(), Set.of("SADNESS"))).isEqualTo(0.0);
    }

    @Test
    void songRagaAccuracyCountsUntaggedSongsAsMisses() {
        List<TrackDTO> top = List.of(song("SADNESS", "Darbari Kanada", null), song("SADNESS", "yaman", null),
                song("SADNESS", "Hindol", null), song("SADNESS", null, null));
        assertThat(EvaluationScoring.songRagaAccuracy(top, List.of("Darbari Kanada", "Yaman"))).isEqualTo(0.5);
        assertThat(EvaluationScoring.ragaTaggedShare(top)).isEqualTo(0.75);
    }

    @Test
    void genreAccuracyIsNullWithoutGenreLabels() {
        List<TrackDTO> top = List.of(song("SADNESS", null, "Ghazal"), song("SADNESS", null, "bollywood"),
                song("SADNESS", null, "Rock"));
        assertThat(EvaluationScoring.genreAccuracy(top, List.of("Ghazal", "Bollywood"))).isEqualTo(0.6667);
        assertThat(EvaluationScoring.genreAccuracy(top, List.of())).isNull();
        assertThat(EvaluationScoring.genreAccuracy(top, null)).isNull();
    }

    @Test
    void humanJudgmentsReplaceMetadataRelevanceAndUnjudgedSongsCountZero() {
        Track judged = new Track(); judged.setId(1L); judged.setTitle("Channa Mereya"); judged.setPrimaryEmotion("JOY");
        Track unjudged = new Track(); unjudged.setId(2L); unjudged.setTitle("Tum Hi Ho"); unjudged.setPrimaryEmotion("SADNESS");

        Map<Long, Integer> rel = EvaluationScoring.humanRelevance(List.of(judged, unjudged), Map.of("channa mereya ", 3));

        assertThat(rel).containsEntry(1L, 3).containsEntry(2L, 0);
    }

    /** Guards the shipped dataset: it parses, uses only canonical labels, and has no ambiguous dominant emotion. */
    @Test
    void shippedDatasetIsValid() throws Exception {
        EvaluationDataset ds = new ObjectMapper().readValue(
                new File("../evaluation/dataset/conversations.json"), EvaluationDataset.class);
        assertThat(ds.cases()).isNotEmpty();
        assertThat(ds.cases()).extracting(EvaluationDataset.Case::id).doesNotHaveDuplicates();
        for (EvaluationDataset.Case c : ds.cases()) {
            List<String> labels = EvaluationScoring.patientLabels(c);
            assertThat(labels).as("case %s patient labels", c.id()).isNotEmpty().doesNotContainNull()
                    .allMatch(EmotionLabels.CANONICAL::contains);
            assertThat(EvaluationScoring.expectedDominant(c)).isNotBlank();
        }
    }
}
