package com.musicrec.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.EvidenceRecord;
import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.Track;
import com.musicrec.repository.TrackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implements the Retrieve → Fuse (weighted RRF) → Propose/Assign (evidence) →
 * Lightweight Rerank → Select (Groq LLM) pipeline.
 *
 * ┌───────────────┐ ┌────────────┐ ┌────────────────────┐ ┌──────────────┐ ┌──────────────────────┐
 * │ RETRIEVE       │ │ FUSE       │ │ PROPOSE + ASSIGN    │ │ LIGHTWEIGHT  │ │ SELECT (Groq LLM)     │
 * │ N independent  │ │ weighted   │ │ Code-verified evi-  │ │ RERANK       │ │ Picks top-10, writes  │
 * │ MySQL sources  │ │ RRF score  │ │ dence per track,    │ │ Code-only    │ │ explanations grounded │
 * │                │ │ = Σ w·1/(k+r)│ capped top-3        │ │ shortlist    │ │ ONLY in that evidence │
 * └───────────────┘ └────────────┘ └────────────────────┘ └──────────────┘ └──────────────────────┘
 *
 * Weighted Reciprocal Rank Fusion (Cormack et al., 2009, extended with source weights):
 *   score(track) = Σ over sources w(source) / (k + rank_in_that_source).
 *   A high-precision source like an exact emotion+language match is weighted
 *   above a fuzzy source like a single keyword tag, so agreement between two
 *   high-precision sources dominates a track only ever seen by weak sources.
 *
 * Propose-Assign-Select (evidence-grounded response generation):
 *   PROPOSE and ASSIGN run in {@link EvidenceGroundingService} as plain code —
 *   no LLM call, so evidence can never be hallucinated. Before the one LLM
 *   call, {@link LightweightRerankerService} trims the fused pool to a
 *   smaller shortlist using RRF score + evidence strength (also code-only,
 *   no model call) — a distinct stage from SELECT, which is constrained to
 *   cite only the evidence it was given.
 */
@Service
@Slf4j
public class MusicRecommendationService {

    private final TrackRepository trackRepository;
    private final SemanticRetrievalService semanticRetrievalService;
    private final EvidenceGroundingService evidenceGroundingService;
    private final LightweightRerankerService lightweightRerankerService;
    private final ChatClient rerankerChatClient;
    private final ObjectMapper objectMapper;

    // RRF smoothing constant — k=60 from the original paper.
    // Prevents the top-ranked document from dominating when only one
    // source retrieved it.
    private static final int RRF_K = 60;

    // Per-source weights for weighted RRF — higher-precision retrieval
    // signals count for more than fuzzy/partial-match ones.
    private static final Map<String, Double> SOURCE_WEIGHTS = Map.of(
            "emotion_lang",       1.5,   // exact primary emotion + language match
            "emotion_any",        1.2,   // exact primary emotion match
            "valence_energy",     0.9,   // audio-feature range match
            "semantic_dense",     0.7,   // fuzzy TF-IDF text similarity
            "catalogue_fallback", 1.0
    );
    private static final double SECONDARY_EMOTION_WEIGHT = 1.0;
    private static final double KEYWORD_TAG_WEIGHT        = 0.8;

    @Value("${app.recommendation.candidate-pool-size:30}")
    private int candidatePoolSize;

    @Value("${app.recommendation.top-n-results:10}")
    private int topNResults;

    // ── LLM Select Prompt (SELECT stage of propose-assign-select) ──
    private static final String SELECT_TEMPLATE = """
            You are a music therapist and recommendation expert. Given the user's emotional profile and
            a pool of candidate tracks — each with a pre-verified EVIDENCE list explaining how it matches
            the user — select the TOP %d tracks that will best serve their emotional needs right now.

            USER EMOTIONAL PROFILE:
            - Primary Emotion : %s
            - Intensity        : %.2f  (0=mild, 1=overwhelming)
            - Secondary Emotions: %s
            - Language Preference: %s
            - Context           : %s
            - Mood Keywords     : %s

            CANDIDATE TRACKS WITH EVIDENCE (JSON):
            %s

            SELECTION GUIDELINES:
            1. Prefer tracks with MORE and STRONGER evidence entries.
            2. For HIGH intensity stress/anger: prefer calming tracks — check for a "valence_energy_fit" entry.
            3. For sadness: a blend of empathetic-sad + hopeful tracks works best.
            4. For joy/excitement: high energy, high valence tracks.
            5. Ensure at least 30%% of picks match the user's detected language.
            6. Rank by overall emotional fit, not just popularity.
            7. Provide diversity: mix genres where appropriate.

            EVIDENCE RULE (STRICT):
            - Every track_explanation you write MUST be grounded ONLY in that track's own "evidence" list.
            - Do NOT invent facts, claims, or emotional connections that are not present in its evidence.
            - If a track's evidence list is empty, base its explanation only on genre/popularity — never
              fabricate an emotional match for it.

            Return ONLY this JSON — no markdown, no extra text:
            {
              "selected_track_ids": [<id1>, <id2>, ..., <id%d>],
              "recommendation_message": "<warm 2-3 sentence message in %s explaining why these songs fit the mood>",
              "track_explanations": {
                "<id>": "<one short sentence, grounded ONLY in that track's evidence, explaining why it fits>"
              }
            }
            """;

    public MusicRecommendationService(
            TrackRepository trackRepository,
            SemanticRetrievalService semanticRetrievalService,
            EvidenceGroundingService evidenceGroundingService,
            LightweightRerankerService lightweightRerankerService,
            @Qualifier("rerankerChatClient") ChatClient rerankerChatClient,
            ObjectMapper objectMapper) {
        this.trackRepository           = trackRepository;
        this.semanticRetrievalService  = semanticRetrievalService;
        this.evidenceGroundingService  = evidenceGroundingService;
        this.lightweightRerankerService = lightweightRerankerService;
        this.rerankerChatClient        = rerankerChatClient;
        this.objectMapper              = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────

    public RankingResult recommend(EmotionMetadata emotion) {
        // ── STEP 1: RETRIEVE ────────────────────────────────────────
        Map<String, List<Track>> sources = retrieve(emotion);

        long totalCandidates = sources.values().stream().mapToLong(List::size).sum();
        log.info("RETRIEVE: {} candidates across {} sources for emotion={}",
                totalCandidates, sources.size(), emotion.getPrimaryEmotion());

        // Fallback: if every source is empty, use the full catalogue
        boolean allEmpty = sources.values().stream().allMatch(List::isEmpty);
        if (allEmpty) {
            log.warn("All retrieval sources empty — falling back to full catalogue");
            List<Track> fallback = trackRepository.findAll().stream()
                    .sorted(Comparator.comparingInt(Track::getPopularity).reversed())
                    .limit(candidatePoolSize)
                    .toList();
            sources = new LinkedHashMap<>();
            sources.put("catalogue_fallback", fallback);
        }

        // ── STEP 2: FUSE via weighted Reciprocal Rank Fusion ─────────
        FuseResult fuseResult = rrf(sources);
        List<Track> fused = fuseResult.tracks();
        log.info("RRF FUSE: {} candidates after fusion (pool cap={})",
                fused.size(), candidatePoolSize);

        // ── STEP 3: PROPOSE + ASSIGN evidence (pure code, no LLM) ────
        Map<Long, Integer> sourceHitCounts = countSourceHits(sources);
        List<EvidenceRecord> evidence = evidenceGroundingService.propose(fused, emotion, sourceHitCounts);
        Map<Long, List<EvidenceRecord>> evidenceByTrack = evidenceGroundingService.assign(evidence);
        log.info("EVIDENCE: {} facts proposed, assigned across {} of {} candidates",
                evidence.size(), evidenceByTrack.size(), fused.size());

        // ── STEP 4: LIGHTWEIGHT RERANK — cheap, code-only shortlist ──
        List<Track> shortlisted = lightweightRerankerService.rerank(fused, fuseResult.scores(), evidenceByTrack);

        // ── STEP 5: SELECT (Groq LLM, constrained to the assigned evidence) ─
        return select(shortlisted, evidenceByTrack, emotion);
    }

    /** Counts, per track id, how many independent retrieval sources surfaced it. */
    private Map<Long, Integer> countSourceHits(Map<String, List<Track>> sources) {
        Map<Long, Integer> counts = new HashMap<>();
        sources.values().forEach(list ->
                list.stream().map(Track::getId).distinct()
                        .forEach(id -> counts.merge(id, 1, Integer::sum)));
        return counts;
    }

    // ── Pipeline Stages ──────────────────────────────────────────

    /**
     * RETRIEVE — runs independent queries for each retrieval signal.
     * Each query is a separate "source" for RRF.
     * Returns a map of sourceName → ranked track list.
     */
    private Map<String, List<Track>> retrieve(EmotionMetadata emotion) {
        Map<String, List<Track>> sources = new LinkedHashMap<>();

        String primaryEmotion = emotion.getPrimaryEmotionSafe();
        String language       = emotion.getLanguageSafe();

        // Source 1: primary emotion + language match (strongest signal)
        sources.put("emotion_lang",
                trackRepository.findByPrimaryEmotionIgnoreCaseAndLanguageIgnoreCaseOrderByPopularityDesc(
                        primaryEmotion, language));

        // Source 2: primary emotion, any language
        sources.put("emotion_any",
                trackRepository.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc(primaryEmotion));

        // Sources 3+: one source per secondary emotion
        if (emotion.getSecondaryEmotions() != null) {
            int i = 0;
            for (String sec : emotion.getSecondaryEmotions()) {
                sources.put("secondary_" + i++,
                        trackRepository.findBySecondaryEmotionContains(sec.toUpperCase(), 15));
            }
        }

        // Sources N+: one source per mood keyword tag (top 3 keywords)
        if (emotion.getMoodKeywords() != null) {
            int i = 0;
            for (String kw : emotion.getMoodKeywords().stream().limit(3).toList()) {
                sources.put("keyword_" + i++,
                        trackRepository.findByMoodTagContains(kw.toLowerCase(), 8));
            }
        }

        // Source: valence/energy range fallback (fills sparse pools)
        double[] ranges = evidenceGroundingService.valenceEnergyRange(primaryEmotion);
        sources.put("valence_energy",
                trackRepository.findByValenceAndEnergy(
                        ranges[0], ranges[1], ranges[2], ranges[3]));

        // Source last: dense retrieval — TF-IDF cosine similarity over the whole
        // catalogue using the expanded emotion/mood-tag profile. Complements the
        // exact-match "lexical" sources above with fuzzy/partial textual matches.
        sources.put("semantic_dense",
                semanticRetrievalService.retrieveBySimilarity(emotion, 20));

        // Log how many each source contributed
        sources.forEach((name, tracks) ->
                log.debug("  source[{}] → {} tracks", name, tracks.size()));

        return sources;
    }

    /**
     * FUSE — Weighted Reciprocal Rank Fusion across all retrieval sources.
     *
     * For each track that appears in any source:
     *   score += sourceWeight(source) / (RRF_K + rank_in_that_source)
     *
     * A track seen at rank 1 by two high-precision sources beats a track
     * seen at rank 1 by only one fuzzy/low-precision source.
     */
    private FuseResult rrf(Map<String, List<Track>> sources) {
        Map<Long, Double> rrfScores = new HashMap<>();
        Map<Long, Track>  trackById = new HashMap<>();

        for (Map.Entry<String, List<Track>> entry : sources.entrySet()) {
            String sourceName = entry.getKey();
            double weight     = sourceWeight(sourceName);
            List<Track> rankedList = entry.getValue();
            for (int rank = 0; rank < rankedList.size(); rank++) {
                Track t     = rankedList.get(rank);
                double score = weight / (RRF_K + rank + 1);   // rank is 0-indexed → +1
                rrfScores.merge(t.getId(), score, Double::sum);
                trackById.put(t.getId(), t);
            }
        }

        // Sort by RRF score descending, cap at candidatePoolSize
        List<Track> fused = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(candidatePoolSize)
                .map(e -> trackById.get(e.getKey()))
                .collect(Collectors.toList());

        if (log.isDebugEnabled()) {
            fused.forEach(t -> log.debug("  rrf[{}] score={:.4f} title={}",
                    t.getId(),
                    rrfScores.get(t.getId()),
                    t.getTitle()));
        }

        return new FuseResult(fused, rrfScores);
    }

    /** Per-source RRF weight — exact/high-precision sources outweigh fuzzy ones. */
    private double sourceWeight(String sourceName) {
        Double explicit = SOURCE_WEIGHTS.get(sourceName);
        if (explicit != null) return explicit;
        if (sourceName.startsWith("secondary_")) return SECONDARY_EMOTION_WEIGHT;
        if (sourceName.startsWith("keyword_"))   return KEYWORD_TAG_WEIGHT;
        return 1.0;
    }

    /** Result of the FUSE stage: RRF-ordered candidates plus each track's raw RRF score. */
    private record FuseResult(List<Track> tracks, Map<Long, Double> scores) {}

    /** SELECT — sends the evidence-annotated candidates to Groq and parses the top-N result */
    private RankingResult select(List<Track> candidates,
                                  Map<Long, List<EvidenceRecord>> evidenceByTrack,
                                  EmotionMetadata emotion) {
        try {
            List<Map<String, Object>> compactTracks = candidates.stream()
                    .map(t -> toCompactMap(t, evidenceByTrack.getOrDefault(t.getId(), List.of())))
                    .toList();

            String candidatesJson = objectMapper.writeValueAsString(compactTracks);
            String safeJson       = candidatesJson.replace("%", "%%");
            String language       = emotion.getLanguageSafe();

            String prompt = SELECT_TEMPLATE.formatted(
                    topNResults,
                    emotion.getPrimaryEmotionSafe(),
                    emotion.getIntensity(),
                    formatList(emotion.getSecondaryEmotions()),
                    language,
                    nullSafe(emotion.getContextSummary()),
                    formatList(emotion.getMoodKeywords()),
                    safeJson,
                    topNResults,
                    language
            );

            log.debug("Sending {} evidence-annotated candidates to Groq for selection", candidates.size());

            String rawJson = rerankerChatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return parseSelectResponse(rawJson, candidates, emotion);

        } catch (Exception ex) {
            log.warn("LLM selection failed — falling back to RRF order. Cause: {}", ex.getMessage());
            return rrfFallback(candidates, emotion);
        }
    }

    // ── Response Parsing ─────────────────────────────────────────

    private RankingResult parseSelectResponse(String rawJson,
                                               List<Track> candidates,
                                               EmotionMetadata emotion) {
        try {
            String cleaned = extractJsonBlock(rawJson);
            JsonNode root  = objectMapper.readTree(cleaned);

            Map<Long, Track> trackMap = candidates.stream()
                    .collect(Collectors.toMap(Track::getId, t -> t));

            List<Long> selectedIds = new ArrayList<>();
            root.path("selected_track_ids").forEach(node -> selectedIds.add(node.asLong()));

            Map<String, String> explanations = new HashMap<>();
            JsonNode expNode = root.path("track_explanations");
            if (!expNode.isMissingNode()) {
                expNode.fields().forEachRemaining(e ->
                        explanations.put(e.getKey(), e.getValue().asText()));
            }

            List<TrackDTO> ordered = selectedIds.stream()
                    .filter(trackMap::containsKey)
                    .limit(topNResults)
                    .map(id -> {
                        TrackDTO dto = TrackDTO.from(trackMap.get(id));
                        dto.setExplanation(explanations.getOrDefault(String.valueOf(id), ""));
                        return dto;
                    })
                    .collect(Collectors.toList());

            // Pad with RRF-ordered tracks if LLM returned fewer than topNResults
            if (ordered.size() < topNResults) {
                Set<Long> usedIds = ordered.stream().map(TrackDTO::getId).collect(Collectors.toSet());
                candidates.stream()
                        .filter(t -> !usedIds.contains(t.getId()))
                        .limit(topNResults - ordered.size())
                        .forEach(t -> ordered.add(TrackDTO.from(t)));
            }

            String message = root.path("recommendation_message").asText(defaultMessage(emotion));
            log.info("SELECT: Groq selected {} tracks", ordered.size());
            return new RankingResult(ordered, message);

        } catch (Exception ex) {
            log.warn("Failed to parse rerank response: {}", ex.getMessage());
            return rrfFallback(candidates, emotion);
        }
    }

    /** Fallback when LLM reranking fails — return candidates in RRF order */
    private RankingResult rrfFallback(List<Track> candidates, EmotionMetadata emotion) {
        List<TrackDTO> top = candidates.stream()
                .limit(topNResults)
                .map(TrackDTO::from)
                .collect(Collectors.toList());
        return new RankingResult(top, defaultMessage(emotion));
    }

    // ── Utilities ─────────────────────────────────────────────────

    private Map<String, Object> toCompactMap(Track t, List<EvidenceRecord> evidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",         t.getId());
        m.put("title",      t.getTitle());
        m.put("artist",     t.getArtist());
        m.put("genre",      t.getGenre());
        m.put("language",   t.getLanguage());
        m.put("popularity", t.getPopularity());
        m.put("evidence",   evidence.stream().map(EvidenceRecord::text).toList());
        return m;
    }

    private String extractJsonBlock(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.strip();
        int start = trimmed.indexOf('{');
        int end   = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    private String formatList(List<String> list) {
        return (list == null || list.isEmpty()) ? "[]"
                : "[" + list.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")) + "]";
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    private String defaultMessage(EmotionMetadata emotion) {
        return "Here are some songs carefully selected to match your current mood. "
                + "I hope they bring you comfort and joy!";
    }

    // ── Result type ───────────────────────────────────────────────

    public record RankingResult(List<TrackDTO> tracks, String conversationalMessage) {}
}
