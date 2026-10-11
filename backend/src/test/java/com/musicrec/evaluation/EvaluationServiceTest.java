package com.musicrec.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.ConversationRecommendationResponse;
import com.musicrec.dto.EmotionalVariabilityDTO;
import com.musicrec.dto.PatientSegmentEmotionDTO;
import com.musicrec.dto.RagaSelectionDTO;
import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.Track;
import com.musicrec.repository.TrackRepository;
import com.musicrec.service.ConversationPipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * EvaluationService against a mocked pipeline: scores are computed from what the pipeline returned,
 * failures and LLM fallbacks are counted rather than hidden, and reports are written to disk.
 */
class EvaluationServiceTest {

    @TempDir
    Path tmp;

    private final ConversationPipelineService pipeline = mock(ConversationPipelineService.class);
    private final TrackRepository trackRepository = mock(TrackRepository.class);
    private EvaluationService service;

    private static final String DATASET = """
            {"name": "unit", "cases": [
              {"id": "S1", "title": "sad", "turns": [
                {"role": "HEALTH_CONSULTANT", "text": "How are you?"},
                {"role": "PATIENT", "text": "sad one", "emotion": "SADNESS"},
                {"role": "HEALTH_CONSULTANT", "text": "And sleep?"},
                {"role": "PATIENT", "text": "sad two", "emotion": "SADNESS"}]},
              {"id": "J1", "title": "joy", "turns": [
                {"role": "PATIENT", "text": "happy", "emotion": "JOY"}]}
            ]}""";

    private Track hindi(long id, String emotion, String raga) {
        Track t = new Track();
        t.setId(id);
        t.setTitle("T" + id);
        t.setLanguage("Hindi");
        t.setPrimaryEmotion(emotion);
        t.setSecondaryEmotions(List.of());
        t.setRaga(raga);
        return t;
    }

    private TrackDTO dto(Track t) {
        return TrackDTO.from(t);
    }

    private ConversationRecommendationResponse response(List<String> segmentEmotions, double confidence,
                                                        String raga, List<Track> tracks, boolean llmSongSelection) {
        List<PatientSegmentEmotionDTO> segments = new ArrayList<>();
        for (int i = 0; i < segmentEmotions.size(); i++) {
            segments.add(PatientSegmentEmotionDTO.builder().order(i).primaryEmotion(segmentEmotions.get(i))
                    .intensity(0.6).confidence(confidence).build());
        }
        Map<String, Double> dist = EvaluationScoring.distribution(segmentEmotions);
        return ConversationRecommendationResponse.builder()
                .emotionalVariability(EmotionalVariabilityDTO.builder().segments(segments)
                        .dominantEmotion(dist.keySet().iterator().next()).emotionDistribution(new LinkedHashMap<>(dist)).build())
                .ragaSelection(RagaSelectionDTO.builder().raga(raga).alternateRagas(List.of()).warnings(List.of()).build())
                .recommendations(tracks.stream().map(this::dto).toList())
                .llmSongSelection(llmSongSelection)
                .warnings(new ArrayList<>())
                .build();
    }

    private final List<Track> catalogue = List.of(
            hindi(1, "SADNESS", "Darbari Kanada"), hindi(2, "SADNESS", null), hindi(3, "JOY", null), hindi(4, "LOVE", "Yaman"));

    @BeforeEach
    void setUp() throws Exception {
        Path dataset = tmp.resolve("dataset.json");
        Files.writeString(dataset, DATASET);
        when(trackRepository.findByLanguageIgnoreCaseOrderByPopularityDesc("Hindi")).thenReturn(catalogue);

        service = new EvaluationService(pipeline, trackRepository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "datasetPath", dataset.toString());
        ReflectionTestUtils.setField(service, "resultsDir", tmp.resolve("results").toString());
        ReflectionTestUtils.setField(service, "delayBetweenCasesMs", 0L);
        ReflectionTestUtils.setField(service, "k", 10);
        ReflectionTestUtils.setField(service, "llmModel", "test-model");
        ReflectionTestUtils.setField(service, "retryWaitMs", 0L);
        ReflectionTestUtils.setField(service, "maxAttempts", 2);
        ReflectionTestUtils.setField(service, "abortAfterFallbackCases", 0);
    }

    private void pipelineReturns(String sessionSuffix, ConversationRecommendationResponse r) {
        when(pipeline.run(anyString(), anyList(), anyList(), anyString(), anyBoolean(), anyList(),
                eq("eval-" + sessionSuffix), isNull(), eq(false))).thenReturn(r);
    }

    @Test
    void perfectCaseScoresFullMarksAndWritesReports() throws Exception {
        pipelineReturns("S1", response(List.of("SADNESS", "SADNESS"), 0.9, "Darbari Kanada",
                List.of(catalogue.get(0), catalogue.get(1)), true));

        EvaluationReport report = service.run(List.of("S1"), null);

        assertThat(report.casesCompleted()).isEqualTo(1);
        EvaluationReport.CaseResult c = report.cases().get(0);
        assertThat(c.segmentsCorrect()).isEqualTo(2);
        assertThat(c.dominantCorrect()).isTrue();
        assertThat(c.ragaTop1Hit()).isTrue();
        assertThat(c.precisionAtK()).isEqualTo(1.0);
        assertThat(c.ndcgAtK()).isEqualTo(1.0);
        assertThat(c.emotionShareFidelity()).isEqualTo(1.0);
        assertThat(report.endToEndAccuracy()).isEqualTo(1.0);
        assertThat(report.casesWithLlmFallback()).isZero();

        try (var files = Files.list(tmp.resolve("results"))) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .anyMatch(n -> n.endsWith(".json")).anyMatch(n -> n.endsWith(".md"));
        }
    }

    @Test
    void wrongEmotionAndOffTargetSongsScoreZero() throws Exception {
        pipelineReturns("S1", response(List.of("JOY", "JOY"), 0.9, "Hindol", List.of(catalogue.get(2)), true));

        EvaluationReport.CaseResult c = service.run(List.of("S1"), null).cases().get(0);

        assertThat(c.segmentsCorrect()).isZero();
        assertThat(c.dominantCorrect()).isFalse();
        assertThat(c.ragaTop1Hit()).isFalse();
        assertThat(c.precisionAtK()).isZero();
        assertThat(c.ndcgAtK()).isZero();
    }

    @Test
    void songSelectionFallbackIsCountedAndFlagged() throws Exception {
        pipelineReturns("S1", response(List.of("SADNESS", "SADNESS"), 0.9, "Darbari Kanada",
                List.of(catalogue.get(0)), false));

        EvaluationReport report = service.run(List.of("S1"), null);

        assertThat(report.cases().get(0).llmSongSelectionUsed()).isFalse();
        assertThat(report.casesWithLlmFallback()).isEqualTo(1);
        assertThat(report.notes()).anyMatch(n -> n.contains("song selection"));
    }

    @Test
    void keywordFallbackEmotionIsCountedAndFlagged() throws Exception {
        pipelineReturns("S1", response(List.of("SADNESS", "SADNESS"), 0.3, "Darbari Kanada",
                List.of(catalogue.get(0)), true));

        EvaluationReport report = service.run(List.of("S1"), null);

        assertThat(report.cases().get(0).keywordFallbackSegments()).isEqualTo(2);
        assertThat(report.casesWithLlmFallback()).isEqualTo(1);
    }

    @Test
    void aFailingCaseIsRecordedWithoutStoppingTheRun() throws Exception {
        pipelineReturns("S1", response(List.of("SADNESS", "SADNESS"), 0.9, "Darbari Kanada",
                List.of(catalogue.get(0)), true));
        when(pipeline.run(anyString(), anyList(), anyList(), anyString(), anyBoolean(), anyList(),
                eq("eval-J1"), isNull(), eq(false))).thenThrow(new RuntimeException("boom"));

        EvaluationReport report = service.run(null, null);

        assertThat(report.casesTotal()).isEqualTo(2);
        assertThat(report.casesCompleted()).isEqualTo(1);
        assertThat(report.casesFailed()).isEqualTo(1);
        assertThat(report.cases()).filteredOn(c -> c.id().equals("J1")).first()
                .satisfies(c -> assertThat(c.error()).contains("boom"));
        // aggregates cover completed cases only
        assertThat(report.emotion().dominantAccuracy()).isEqualTo(1.0);
    }

    @Test
    void songLevelRagaAndEmotionAccuracyAreReported() throws Exception {
        // 1 Darbari Kanada SADNESS song (raga ✓ emotion ✓), 1 untagged SADNESS song (emotion ✓), 1 JOY song.
        pipelineReturns("S1", response(List.of("SADNESS", "SADNESS"), 0.9, "Darbari Kanada",
                List.of(catalogue.get(0), catalogue.get(1), catalogue.get(2)), true));

        EvaluationReport report = service.run(List.of("S1"), null);
        EvaluationReport.CaseResult c = report.cases().get(0);

        assertThat(c.songEmotionAccuracy()).isEqualTo(0.6667);
        assertThat(c.songRagaAccuracy()).isEqualTo(0.3333);
        assertThat(c.genreAccuracy()).isNull();
        assertThat(c.relevanceSource()).isEqualTo("metadata");
        assertThat(report.songs().meanSongRagaAccuracy()).isEqualTo(0.3333);
        assertThat(report.songs().meanGenreAccuracy()).isNull();
        assertThat(report.songs().genreLabelledCases()).isZero();
    }

    @Test
    void aRateLimitedCaseIsRetriedAndTheCleanAttemptIsScored() throws Exception {
        ConversationRecommendationResponse limited = response(List.of("JOY", "JOY"), 0.3, "Hindol",
                List.of(catalogue.get(2)), false);
        ConversationRecommendationResponse clean = response(List.of("SADNESS", "SADNESS"), 0.9, "Darbari Kanada",
                List.of(catalogue.get(0), catalogue.get(1)), true);
        when(pipeline.run(anyString(), anyList(), anyList(), anyString(), anyBoolean(), anyList(),
                eq("eval-S1"), isNull(), eq(false))).thenReturn(limited, clean);

        EvaluationReport report = service.run(List.of("S1"), null);

        EvaluationReport.CaseResult c = report.cases().get(0);
        assertThat(c.attempts()).isEqualTo(2);
        assertThat(c.dominantCorrect()).isTrue();
        assertThat(report.casesWithLlmFallback()).isZero();
    }

    @Test
    void aCaseStillRateLimitedAfterAllAttemptsKeepsItsFallbackFlag() throws Exception {
        pipelineReturns("S1", response(List.of("SADNESS", "SADNESS"), 0.3, "Darbari Kanada",
                List.of(catalogue.get(0)), false));

        EvaluationReport report = service.run(List.of("S1"), null);

        assertThat(report.cases().get(0).attempts()).isEqualTo(2);
        assertThat(report.casesWithLlmFallback()).isEqualTo(1);
    }

    @Test
    void runStopsEarlyWhenConsecutiveCasesStillFallBackAfterRetrying() throws Exception {
        // Daily LLM quota exhausted: every attempt falls back. After 1 such case the rest are skipped, not run.
        ReflectionTestUtils.setField(service, "abortAfterFallbackCases", 1);
        when(pipeline.run(anyString(), anyList(), anyList(), anyString(), anyBoolean(), anyList(),
                anyString(), isNull(), eq(false))).thenReturn(response(List.of("SADNESS", "SADNESS"), 0.3,
                "Darbari Kanada", List.of(catalogue.get(0)), false));

        EvaluationReport report = service.run(null, null);

        assertThat(report.casesTotal()).isEqualTo(2);
        assertThat(report.casesCompleted()).isEqualTo(1);
        assertThat(report.cases().get(1).error()).contains("skipped");
        assertThat(report.notes()).anyMatch(n -> n.contains("stopped early"));
        verify(pipeline, times(2)).run(anyString(), anyList(), anyList(), anyString(), anyBoolean(), anyList(),
                anyString(), isNull(), eq(false));   // 2 attempts of case 1, none of case 2
    }

    @Test
    void unknownCaseIdsAreRejected() {
        assertThatThrownBy(() -> service.run(List.of("S1", "NOPE"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    void limitRunsOnlyTheFirstCases() throws Exception {
        pipelineReturns("S1", response(List.of("SADNESS", "SADNESS"), 0.9, "Darbari Kanada",
                List.of(catalogue.get(0)), true));

        EvaluationReport report = service.run(null, 1);

        assertThat(report.casesTotal()).isEqualTo(1);
        assertThat(report.cases().get(0).id()).isEqualTo("S1");
    }
}
