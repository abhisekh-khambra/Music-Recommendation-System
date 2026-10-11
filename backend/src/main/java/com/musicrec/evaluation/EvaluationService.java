package com.musicrec.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.musicrec.dto.ConversationRecommendationResponse;
import com.musicrec.dto.ConversationTurnDTO;
import com.musicrec.dto.EmotionalVariabilityDTO;
import com.musicrec.dto.PatientSegmentEmotionDTO;
import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.Track;
import com.musicrec.evaluation.EvaluationReport.CaseResult;
import com.musicrec.reference.RagaRasaCatalog;
import com.musicrec.repository.TrackRepository;
import com.musicrec.service.ConversationPipelineService;
import com.musicrec.service.EmotionAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Runs every case of a labelled conversation dataset through the SAME conversation pipeline users
 * get (ConversationPipelineService, from speaker-labelled turns onward — no audio, so speaker
 * diarization and acoustic arousal are not part of what is measured here) and scores it at four
 * levels: emotion (per response + dominant), Raga (Top-1/Top-3), songs (Precision/Recall/NDCG@K and
 * emotion-share fidelity) and end-to-end. Writes JSON + Markdown reports to the results directory.
 *
 * Calls the LLM ~3 times per case (overall emotion, per-response emotions, song selection), so a run
 * uses real Groq quota; cases whose emotions came from the keyword fallback are counted and flagged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluationService {

    private static final String RAGA_SOURCE_DATASET = "dataset";
    private static final String RAGA_SOURCE_CATALOGUE = "catalogue";

    private final ConversationPipelineService conversationPipelineService;
    private final TrackRepository trackRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.evaluation.dataset-path:../evaluation/dataset/conversations.json}")
    private String datasetPath;

    @Value("${app.evaluation.results-dir:../evaluation/results}")
    private String resultsDir;

    @Value("${app.evaluation.delay-between-cases-ms:2000}")
    private long delayBetweenCasesMs;

    /** Wait before re-running a case that hit an LLM fallback (Groq's per-minute token window is 60 s). */
    @Value("${app.evaluation.retry-wait-ms:65000}")
    private long retryWaitMs;

    @Value("${app.evaluation.max-attempts:2}")
    private int maxAttempts;

    /**
     * Stop the run after this many consecutive cases still used an LLM fallback after retrying — the
     * signature of an exhausted daily quota, where every further case would only produce flagged results.
     * 0 disables the early stop.
     */
    @Value("${app.evaluation.abort-after-fallback-cases:2}")
    private int abortAfterFallbackCases;

    @Value("${app.recommendation.top-n-results:10}")
    private int k;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String llmModel;

    /**
     * @param caseIds run only these cases (all when null/empty)
     * @param limit   run at most this many cases (all when null)
     */
    public EvaluationReport run(Collection<String> caseIds, Integer limit) throws IOException {
        EvaluationDataset dataset = objectMapper.readValue(Path.of(datasetPath).toFile(), EvaluationDataset.class);
        if (caseIds != null && !caseIds.isEmpty()) {
            Set<String> known = dataset.cases().stream().map(EvaluationDataset.Case::id).collect(Collectors.toSet());
            List<String> unknown = caseIds.stream().filter(id -> !known.contains(id)).toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("Unknown evaluation case id(s): " + String.join(", ", unknown)
                        + ". Known ids: " + known.stream().sorted().collect(Collectors.joining(", ")));
            }
        }
        List<EvaluationDataset.Case> cases = dataset.cases().stream()
                .filter(c -> caseIds == null || caseIds.isEmpty() || caseIds.contains(c.id()))
                .limit(limit == null ? Long.MAX_VALUE : limit)
                .toList();

        List<Track> hindiCatalogue = trackRepository.findByLanguageIgnoreCaseOrderByPopularityDesc("Hindi");
        Set<String> hindiRagas = hindiCatalogue.stream().map(Track::getRaga).filter(Objects::nonNull)
                .map(String::toUpperCase).collect(Collectors.toSet());

        log.info("EVALUATION: running {} case(s) from '{}' ({} Hindi tracks, {} Hindi ragas)",
                cases.size(), dataset.name(), hindiCatalogue.size(), hindiRagas.size());

        List<CaseResult> results = new ArrayList<>();
        int consecutiveFallbacks = 0;
        boolean stoppedEarly = false;
        for (int i = 0; i < cases.size(); i++) {
            EvaluationDataset.Case c = cases.get(i);
            if (stoppedEarly) {
                results.add(CaseResult.failed(c.id(), c.title(), "skipped: the run stopped early because the LLM kept "
                        + "falling back (likely the daily Groq token quota) — re-run this case later."));
                continue;
            }
            if (i > 0 && delayBetweenCasesMs > 0) pause(delayBetweenCasesMs);
            try {
                CaseResult r = evaluateCase(c, hindiCatalogue, hindiRagas);
                results.add(r);
                consecutiveFallbacks = r.usedLlmFallback() ? consecutiveFallbacks + 1 : 0;
                log.info("EVALUATION: case {} done ({}/{})", c.id(), i + 1, cases.size());
            } catch (Exception ex) {
                log.warn("EVALUATION: case {} failed: {}", c.id(), ex.getMessage());
                results.add(CaseResult.failed(c.id(), c.title(), ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            }
            if (abortAfterFallbackCases > 0 && consecutiveFallbacks >= abortAfterFallbackCases) {
                stoppedEarly = true;
                log.warn("EVALUATION: {} consecutive case(s) fell back after retrying — stopping the run early", consecutiveFallbacks);
            }
        }

        EvaluationReport report = aggregate(dataset.name(), results, stoppedEarly);
        write(report);
        return report;
    }

    // ── One case ──────────────────────────────────────────────────

    private CaseResult evaluateCase(EvaluationDataset.Case c, List<Track> hindiCatalogue, Set<String> hindiRagas) {
        List<ConversationTurnDTO> turns = new ArrayList<>();
        for (int i = 0; i < c.turns().size(); i++) {
            EvaluationDataset.Turn t = c.turns().get(i);
            String role = ConversationTurnDTO.HEALTH_CONSULTANT.equalsIgnoreCase(t.role())
                    ? ConversationTurnDTO.HEALTH_CONSULTANT : ConversationTurnDTO.PATIENT;
            turns.add(ConversationTurnDTO.withoutTimestamps(i, role, t.text()));
        }
        String transcript = turns.stream().map(ConversationTurnDTO::text).collect(Collectors.joining(" "));

        // A rate-limited LLM call silently degrades to a fallback; re-run such a case after the
        // per-minute window resets so the score reflects the LLM pipeline whenever possible.
        List<String> warnings = new ArrayList<>();
        ConversationRecommendationResponse resp = null;
        int attempts = 0;
        while (attempts < Math.max(1, maxAttempts)) {
            if (attempts > 0) {
                log.info("EVALUATION: case {} used an LLM fallback — retrying in {} ms", c.id(), retryWaitMs);
                pause(retryWaitMs);
            }
            warnings = new ArrayList<>();
            resp = conversationPipelineService.run(
                    transcript, turns, List.of(), "Evaluation run: labelled transcript, no audio.",
                    false, warnings, "eval-" + c.id(), null, false);
            attempts++;
            if (!usedFallback(resp)) break;
        }

        // ── Emotion level ──
        List<String> labels = EvaluationScoring.patientLabels(c);
        EmotionalVariabilityDTO variability = resp.getEmotionalVariability();
        List<PatientSegmentEmotionDTO> segments = variability.getSegments() == null ? List.of() : variability.getSegments();
        List<String> predictions = segments.stream().map(PatientSegmentEmotionDTO::getPrimaryEmotion).toList();
        int segmentsTotal = 0, segmentsCorrect = 0;
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i) == null) continue;
            segmentsTotal++;
            if (i < predictions.size() && labels.get(i).equalsIgnoreCase(predictions.get(i))) segmentsCorrect++;
        }
        int keywordFallbackSegments = (int) segments.stream()
                .filter(s -> s.getConfidence() <= EmotionAnalysisService.KEYWORD_FALLBACK_CONFIDENCE).count();

        String expectedDominant = EvaluationScoring.expectedDominant(c);
        String detectedDominant = variability.getDominantEmotion();
        Map<String, Double> expectedDist = EvaluationScoring.distribution(labels);
        Map<String, Double> detectedDist = variability.getEmotionDistribution() == null
                ? Map.of() : variability.getEmotionDistribution();

        // ── Raga level ──
        boolean expertRagas = c.expectedRagas() != null && !c.expectedRagas().isEmpty();
        List<String> acceptable = expertRagas ? c.expectedRagas()
                : RagaRasaCatalog.ragasForEmotions(List.of(expectedDominant));
        boolean reachable = acceptable.stream().anyMatch(r -> hindiRagas.contains(r.toUpperCase()));
        String selected = resp.getRagaSelection().getRaga();
        List<String> ranked = new ArrayList<>();
        ranked.add(selected);
        if (resp.getRagaSelection().getAlternateRagas() != null) ranked.addAll(resp.getRagaSelection().getAlternateRagas());

        // ── Song level ──
        List<TrackDTO> tracks = resp.getRecommendations() == null ? List.of() : resp.getRecommendations();
        List<Long> ids = tracks.stream().map(TrackDTO::getId).toList();
        Set<String> expectedEmotions = expectedDist.keySet();
        boolean humanJudged = c.songJudgments() != null && !c.songJudgments().isEmpty();
        Map<Long, Integer> relevance = humanJudged
                ? EvaluationScoring.humanRelevance(hindiCatalogue, c.songJudgments())
                : EvaluationScoring.relevanceJudgments(hindiCatalogue, expectedEmotions, acceptable);
        // Songs are judged against every expected emotion's ragas (one raga per emotion share).
        List<String> acceptableSongRagas = expertRagas ? c.expectedRagas()
                : RagaRasaCatalog.ragasForEmotions(expectedEmotions);
        int totalRelevant = (int) relevance.values().stream().filter(g -> g > 0).count();
        Map<String, Double> composition = EvaluationScoring.playlistComposition(tracks, expectedEmotions);

        return new CaseResult(
                c.id(), c.title(), null,
                expectedDominant, detectedDominant, expectedDominant.equalsIgnoreCase(detectedDominant),
                labels, predictions, segmentsCorrect, segmentsTotal,
                expectedDist, detectedDist, EvaluationScoring.distributionSimilarity(expectedDist, detectedDist),
                selected, ranked, acceptable, expertRagas ? RAGA_SOURCE_DATASET : RAGA_SOURCE_CATALOGUE, reachable,
                RagaMappingEvaluator.hitAtK(ranked, acceptable, 1), RagaMappingEvaluator.hitAtK(ranked, acceptable, 3),
                ids, tracks.stream().map(TrackDTO::getTitle).toList(),
                RankingMetrics.precisionAtK(ids, relevance, k),
                RankingMetrics.recallAtK(ids, relevance, k, totalRelevant),
                RankingMetrics.ndcgAtK(ids, relevance, k),
                composition, EvaluationScoring.distributionSimilarity(expectedDist, composition),
                EvaluationScoring.songEmotionAccuracy(tracks, expectedEmotions),
                EvaluationScoring.songRagaAccuracy(tracks, acceptableSongRagas),
                EvaluationScoring.ragaTaggedShare(tracks),
                EvaluationScoring.genreAccuracy(tracks, c.expectedGenres()),
                humanJudged ? "human" : "metadata",
                keywordFallbackSegments, resp.isLlmSongSelection(), attempts, warnings);
    }

    private static boolean usedFallback(ConversationRecommendationResponse resp) {
        List<PatientSegmentEmotionDTO> segs = resp.getEmotionalVariability() == null
                || resp.getEmotionalVariability().getSegments() == null
                ? List.of() : resp.getEmotionalVariability().getSegments();
        return !resp.isLlmSongSelection()
                || segs.stream().anyMatch(s -> s.getConfidence() <= EmotionAnalysisService.KEYWORD_FALLBACK_CONFIDENCE);
    }

    // ── Aggregation ───────────────────────────────────────────────

    private EvaluationReport aggregate(String datasetName, List<CaseResult> all, boolean stoppedEarly) {
        List<CaseResult> done = all.stream().filter(CaseResult::completed).toList();

        int segCorrect = done.stream().mapToInt(CaseResult::segmentsCorrect).sum();
        int segTotal = done.stream().mapToInt(CaseResult::segmentsTotal).sum();
        EvaluationReport.EmotionLevel emotion = new EvaluationReport.EmotionLevel(
                ratio(segCorrect, segTotal), segCorrect, segTotal,
                rate(done, CaseResult::dominantCorrect),
                mean(done, CaseResult::distributionSimilarity));

        List<CaseResult> reachable = done.stream().filter(CaseResult::ragaReachable).toList();
        RagaMappingEvaluator.Report agreement = RagaMappingEvaluator.evaluate(done.stream()
                .map(r -> new RagaMappingEvaluator.Selection(r.selectedRaga(), r.detectedDistribution().keySet()))
                .toList());
        EvaluationReport.RagaLevel raga = new EvaluationReport.RagaLevel(
                rate(done, CaseResult::ragaTop1Hit), rate(done, CaseResult::ragaTop3Hit),
                rate(reachable, CaseResult::ragaTop1Hit), reachable.size(),
                agreement.agreementRate());

        List<CaseResult> genreLabelled = done.stream().filter(r -> r.genreAccuracy() != null).toList();
        EvaluationReport.SongLevel songs = new EvaluationReport.SongLevel(
                mean(done, CaseResult::precisionAtK), mean(done, CaseResult::recallAtK),
                mean(done, CaseResult::ndcgAtK), mean(done, CaseResult::emotionShareFidelity),
                mean(done, CaseResult::songEmotionAccuracy), mean(done, CaseResult::songRagaAccuracy),
                mean(done, CaseResult::ragaTaggedShare),
                genreLabelled.isEmpty() ? null : mean(genreLabelled, CaseResult::genreAccuracy),
                genreLabelled.size(),
                (int) done.stream().filter(r -> "human".equals(r.relevanceSource())).count());

        double endToEnd = rate(done, r -> r.dominantCorrect() && r.ragaTop1Hit());

        Map<String, EvaluationReport.EmotionBreakdown> byEmotion = new TreeMap<>();
        done.stream().collect(Collectors.groupingBy(CaseResult::expectedDominant)).forEach((e, rs) ->
                byEmotion.put(e, new EvaluationReport.EmotionBreakdown(rs.size(),
                        rate(rs, CaseResult::dominantCorrect), rate(rs, CaseResult::ragaTop1Hit),
                        mean(rs, CaseResult::ndcgAtK))));

        int withFallback = (int) done.stream().filter(CaseResult::usedLlmFallback).count();
        int selectionFallback = (int) done.stream().filter(r -> !r.llmSongSelectionUsed()).count();
        int emotionFallback = (int) done.stream().filter(r -> r.keywordFallbackSegments() > 0).count();

        List<String> notes = new ArrayList<>();
        if (stoppedEarly) {
            notes.add("The run stopped early: " + abortAfterFallbackCases + " consecutive case(s) still used an LLM fallback "
                    + "after retrying, which usually means the daily Groq token quota is used up. Skipped cases are listed "
                    + "as failed; re-run when the quota has reset.");
        }
        notes.add("Emotion ground truth: each Patient line in the dataset was written to express its labelled emotion.");
        notes.add("Raga ground truth: unless a case lists expectedRagas, the acceptable ragas are those RagaRasaCatalog associates "
                + "with the expected dominant emotion. This measures whether the pipeline reaches an appropriate raga end to end; "
                + "it does not validate the raga-emotion mapping itself, which needs expert labels.");
        notes.add("Song relevance is graded from catalogue metadata (primary emotion in the expected set = 2, secondary = 1, "
                + "acceptable raga = +1), over Hindi tracks only since every recommendation is Hindi — a proxy for human "
                + "relevance judgments, except in the " + songs.humanJudgedCases() + " case(s) with human songJudgments.");
        notes.add("Song-level raga accuracy counts songs with no raga tag as misses; "
                + Math.round(songs.meanRagaTaggedShare() * 100) + "% of recommended songs carry a raga tag.");
        if (songs.genreLabelledCases() == 0) {
            notes.add("Genre accuracy is not reported: no case has expectedGenres labels yet.");
        }
        notes.add("Audio is not part of this evaluation: cases are speaker-labelled transcripts, so diarization and acoustic "
                + "arousal are not measured.");
        if (emotionFallback > 0) {
            notes.add(emotionFallback + " case(s) had at least one response analysed by the keyword fallback instead of the LLM "
                    + "(e.g. Groq rate limit) — their emotion scores do not reflect the LLM pipeline. Re-run those cases.");
        }
        if (selectionFallback > 0) {
            notes.add(selectionFallback + " case(s) fell back from LLM song selection to fused-rank order (e.g. Groq rate "
                    + "limit) — their song-level scores do not reflect the LLM pipeline. Re-run those cases.");
        }
        if (reachable.size() < done.size()) {
            notes.add((done.size() - reachable.size()) + " case(s) have no acceptable raga with any Hindi track in the "
                    + "catalogue, so no Hindi-only system could get them right at Top-1. top1AccuracyReachable excludes them.");
        }

        return new EvaluationReport(datasetName, LocalDateTime.now().toString(), llmModel, k,
                all.size(), done.size(), all.size() - done.size(), withFallback,
                emotion, raga, songs, endToEnd, byEmotion, all, notes);
    }

    // ── Output ────────────────────────────────────────────────────

    private void write(EvaluationReport report) throws IOException {
        Path dir = Path.of(resultsDir);
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path json = dir.resolve("eval-" + stamp + ".json");
        Path md = dir.resolve("eval-" + stamp + ".md");
        objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(json.toFile(), report);
        Files.writeString(md, EvaluationMarkdown.render(report), StandardCharsets.UTF_8);
        log.info("EVALUATION: wrote {} and {}", json.toAbsolutePath(), md.toAbsolutePath());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static double rate(List<CaseResult> rs, java.util.function.Predicate<CaseResult> p) {
        return ratio((int) rs.stream().filter(p).count(), rs.size());
    }

    private static double mean(List<CaseResult> rs, java.util.function.ToDoubleFunction<CaseResult> f) {
        return rs.isEmpty() ? 0.0 : EvaluationScoring.round(rs.stream().mapToDouble(f).average().orElse(0));
    }

    private static double ratio(int num, int den) {
        return den == 0 ? 0.0 : EvaluationScoring.round((double) num / den);
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
