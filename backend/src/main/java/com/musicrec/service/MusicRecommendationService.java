package com.musicrec.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.EmotionMetadata;
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
 * Implements the Retrieve → Fuse (RRF) → Rerank pipeline.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  RETRIEVE (N sources)  │  FUSE via RRF  │  RERANK (Groq LLM)  │
 * │  Independent MySQL     │  Score = Σ     │  Top-10 with         │
 * │  queries per signal    │  1/(k+rank_i)  │  conversational msg  │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * Reciprocal Rank Fusion (Cormack et al., 2009):
 *   A track appearing at rank 1 in two sources scores higher than
 *   a track appearing at rank 1 in only one source — no manual
 *   weight tuning required.
 */
@Service
@Slf4j
public class MusicRecommendationService {

    private final TrackRepository trackRepository;
    private final ChatClient rerankerChatClient;
    private final ObjectMapper objectMapper;

    // RRF smoothing constant — k=60 from the original paper.
    // Prevents the top-ranked document from dominating when only one
    // source retrieved it.
    private static final int RRF_K = 60;

    @Value("${app.recommendation.candidate-pool-size:30}")
    private int candidatePoolSize;

    @Value("${app.recommendation.top-n-results:10}")
    private int topNResults;

    // ── LLM Rerank Prompt ─────────────────────────────────────────
    private static final String RERANK_TEMPLATE = """
            You are a music therapist and recommendation expert. Given the user's emotional profile and
            a pool of candidate tracks, select the TOP %d tracks that will best serve their emotional needs right now.

            USER EMOTIONAL PROFILE:
            - Primary Emotion : %s
            - Intensity        : %.2f  (0=mild, 1=overwhelming)
            - Secondary Emotions: %s
            - Language Preference: %s
            - Context           : %s
            - Mood Keywords     : %s

            CANDIDATE TRACKS (JSON):
            %s

            SELECTION GUIDELINES:
            1. Prefer tracks whose primary_emotion or secondary_emotions MATCH the user's state.
            2. For HIGH intensity stress/anger: prefer calming tracks (low energy, high valence).
            3. For sadness: a blend of empathetic-sad + hopeful tracks works best.
            4. For joy/excitement: high energy, high valence tracks.
            5. Ensure at least 30%% of picks match the user's detected language.
            6. Rank by overall emotional fit, not just popularity.
            7. Provide diversity: mix genres where appropriate.

            Return ONLY this JSON — no markdown, no extra text:
            {
              "selected_track_ids": [<id1>, <id2>, ..., <id%d>],
              "recommendation_message": "<warm 2-3 sentence message in %s explaining why these songs fit the mood>",
              "track_explanations": {
                "<id>": "<one short sentence explaining why this specific track fits>"
              }
            }
            """;

    public MusicRecommendationService(
            TrackRepository trackRepository,
            @Qualifier("rerankerChatClient") ChatClient rerankerChatClient,
            ObjectMapper objectMapper) {
        this.trackRepository    = trackRepository;
        this.rerankerChatClient = rerankerChatClient;
        this.objectMapper       = objectMapper;
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

        // ── STEP 2: FUSE via Reciprocal Rank Fusion ─────────────────
        List<Track> fused = rrf(sources);
        log.info("RRF FUSE: {} candidates after fusion (pool cap={})",
                fused.size(), candidatePoolSize);

        // ── STEP 3: RERANK (Groq LLM) ───────────────────────────────
        return rerank(fused, emotion);
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

        // Source last: valence/energy range fallback (fills sparse pools)
        double[] ranges = valenceEnergyRange(primaryEmotion);
        sources.put("valence_energy",
                trackRepository.findByValenceAndEnergy(
                        ranges[0], ranges[1], ranges[2], ranges[3]));

        // Log how many each source contributed
        sources.forEach((name, tracks) ->
                log.debug("  source[{}] → {} tracks", name, tracks.size()));

        return sources;
    }

    /**
     * FUSE — Reciprocal Rank Fusion across all retrieval sources.
     *
     * For each track that appears in any source:
     *   score += 1 / (RRF_K + rank_in_that_source)
     *
     * A track seen at rank 1 by two sources beats a track seen at
     * rank 1 by only one source — without any manual weight tuning.
     */
    private List<Track> rrf(Map<String, List<Track>> sources) {
        Map<Long, Double> rrfScores = new HashMap<>();
        Map<Long, Track>  trackById = new HashMap<>();

        for (Map.Entry<String, List<Track>> entry : sources.entrySet()) {
            List<Track> rankedList = entry.getValue();
            for (int rank = 0; rank < rankedList.size(); rank++) {
                Track t     = rankedList.get(rank);
                double score = 1.0 / (RRF_K + rank + 1);   // rank is 0-indexed → +1
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

        return fused;
    }

    /** RERANK — sends the fused candidate list to Groq and parses the top-N result */
    private RankingResult rerank(List<Track> candidates, EmotionMetadata emotion) {
        try {
            List<Map<String, Object>> compactTracks = candidates.stream()
                    .map(this::toCompactMap)
                    .toList();

            String candidatesJson = objectMapper.writeValueAsString(compactTracks);
            String safeJson       = candidatesJson.replace("%", "%%");
            String language       = emotion.getLanguageSafe();

            String prompt = RERANK_TEMPLATE.formatted(
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

            log.debug("Sending {} RRF-fused candidates to Groq for reranking", candidates.size());

            String rawJson = rerankerChatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return parseRerankResponse(rawJson, candidates, emotion);

        } catch (Exception ex) {
            log.warn("LLM reranking failed — falling back to RRF order. Cause: {}", ex.getMessage());
            return rrfFallback(candidates, emotion);
        }
    }

    // ── Response Parsing ─────────────────────────────────────────

    private RankingResult parseRerankResponse(String rawJson,
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
            log.info("RERANK: Groq selected {} tracks", ordered.size());
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

    private Map<String, Object> toCompactMap(Track t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               t.getId());
        m.put("title",            t.getTitle());
        m.put("artist",           t.getArtist());
        m.put("genre",            t.getGenre());
        m.put("language",         t.getLanguage());
        m.put("primary_emotion",  t.getPrimaryEmotion());
        m.put("secondary_emotions", t.getSecondaryEmotions());
        m.put("mood_tags",        t.getMoodTags());
        m.put("energy_level",     t.getEnergyLevel());
        m.put("valence",          t.getValence());
        m.put("tempo_bpm",        t.getTempoBpm());
        m.put("popularity",       t.getPopularity());
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

    /** Returns [minValence, maxValence, minEnergy, maxEnergy] for an emotion */
    private double[] valenceEnergyRange(String emotion) {
        return switch (emotion.toUpperCase()) {
            case "JOY", "EXCITEMENT"         -> new double[]{0.6, 1.0, 0.5, 1.0};
            case "SADNESS", "MELANCHOLY"     -> new double[]{0.0, 0.4, 0.0, 0.5};
            case "ANGER", "FRUSTRATION"      -> new double[]{0.0, 0.4, 0.7, 1.0};
            case "STRESS", "ANXIETY"         -> new double[]{0.0, 0.5, 0.0, 0.6};
            case "RELAXATION", "CALM"        -> new double[]{0.4, 0.8, 0.0, 0.4};
            case "LOVE", "NOSTALGIA"         -> new double[]{0.4, 0.9, 0.1, 0.6};
            default                          -> new double[]{0.3, 0.8, 0.2, 0.8};
        };
    }

    // ── Result type ───────────────────────────────────────────────

    public record RankingResult(List<TrackDTO> tracks, String conversationalMessage) {}
}
