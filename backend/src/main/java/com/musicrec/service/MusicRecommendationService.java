package com.musicrec.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.EvidenceRecord;
import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.Track;
import com.musicrec.reference.RagaRasaCatalog;
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
            "raga_match",         1.3,   // raga traditionally associated with the detected emotion (rasa theory)
            "valence_energy",     0.9,   // audio-feature range match
            "semantic_dense",     0.7,   // fuzzy TF-IDF text similarity
            "catalogue_fallback", 1.0
    );
    private static final double SECONDARY_EMOTION_WEIGHT = 1.0;
    private static final double KEYWORD_TAG_WEIGHT        = 0.8;
    // Exact primary-emotion match for a non-dominant emotion in the Patient's distribution —
    // as precise as emotion_any, just for a different emotion.
    private static final double DISTRIBUTION_EMOTION_WEIGHT = 1.2;

    // Highest weight of all — the specific Raga chosen by RagaSelectionService for the
    // conversation pipeline. Raga is meant to be the central driver of that pipeline's
    // recommendations, so it must outrank every other retrieval signal.
    private static final double RAGA_PRIMARY_WEIGHT       = 2.0;

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

    /**
     * Hindi-only is now the system-wide default for every input modality (text, voice,
     * conversation) — the language restriction is enforced here, not per-caller, so it
     * can never be silently bypassed regardless of which controller calls this.
     */
    public RankingResult recommend(EmotionMetadata emotion) {
        return recommend(emotion, null, true);
    }

    /**
     * Same pipeline as {@link #recommend(EmotionMetadata)}, but with a specific Raga
     * (from RagaSelectionService) added as the highest-weighted retrieval source —
     * used by the conversation-based pipeline, where Raga is the central selection
     * driving which songs are even considered, not just one signal among many.
     */
    public RankingResult recommend(EmotionMetadata emotion, String primaryRaga) {
        return recommend(emotion, primaryRaga, true);
    }

    /**
     * @param restrictToHindi mandatory Hindi-only mode. When true, every retrieval source, the
     *                        all-empty fallback, and the candidate pool handed to the LLM SELECT
     *                        stage are pre-filtered to language=Hindi — so a non-Hindi track can
     *                        never reach the final list. Defaults to true everywhere via the
     *                        shorter overloads above; callers only need to pass false explicitly
     *                        if they deliberately want the old unrestricted behavior (no caller
     *                        currently does — the restriction is system-wide by design now).
     */
    public RankingResult recommend(EmotionMetadata emotion, String primaryRaga, boolean restrictToHindi) {
        return recommend(emotion, primaryRaga, restrictToHindi, PlaylistPlan.NONE);
    }

    /**
     * How the conversation pipeline's Top-N is shaped: the Patient's emotion distribution (percent of
     * responses per emotion) and the raga chosen for each emotion's share.
     */
    public record PlaylistPlan(Map<String, Double> distribution, Map<String, String> ragaByEmotion) {
        public static final PlaylistPlan NONE = new PlaylistPlan(Map.of(), Map.of());

        boolean splits() {
            return distribution != null && !distribution.isEmpty();
        }
    }

    /**
     * Conversation pipeline: same as above, but the Top-N is split across the Patient's emotions in
     * proportion to their share of the responses (EmotionQuotaAllocator) — e.g. 50% SADNESS gives
     * 5 of 10 songs — each emotion in the distribution gets its own retrieval source, and each
     * share's raga gets its own source too, so the fused pool has songs to fill every share.
     *
     * @param plan emotion distribution + raga per share; {@link PlaylistPlan#NONE} → no split
     */
    public RankingResult recommend(EmotionMetadata emotion, String primaryRaga, boolean restrictToHindi,
                                   PlaylistPlan plan) {
        // ── STEP 1: RETRIEVE ────────────────────────────────────────
        Map<String, List<Track>> sources = retrieve(emotion, primaryRaga, plan);

        if (restrictToHindi) {
            sources = filterToHindi(sources);
        }

        long totalCandidates = sources.values().stream().mapToLong(List::size).sum();
        log.info("RETRIEVE: {} candidates across {} sources for emotion={}{}",
                totalCandidates, sources.size(), emotion.getPrimaryEmotion(),
                restrictToHindi ? " (Hindi-only)" : "");

        // Fallback: if every source is empty, use the full catalogue (or, in Hindi-only
        // mode, the full Hindi-only catalogue — never falls back to non-Hindi tracks).
        boolean allEmpty = sources.values().stream().allMatch(List::isEmpty);
        if (allEmpty) {
            log.warn("All retrieval sources empty — falling back to {} catalogue",
                    restrictToHindi ? "Hindi-only" : "full");
            List<Track> fallback = (restrictToHindi
                        ? trackRepository.findByLanguageIgnoreCaseOrderByPopularityDesc("Hindi")
                        : trackRepository.findAll())
                    .stream()
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
        RankingResult selected = select(shortlisted, evidenceByTrack, emotion);
        if (!plan.splits()) {
            return selected;
        }

        // ── STEP 6: EMOTION-SHARE SPLIT (pure code) ─────────────────
        // Preference order: the LLM's picks first, then the rest of the fused pool in RRF order,
        // so a share the picks can't fill is filled from the next-best fused candidates.
        List<TrackDTO> preference = new ArrayList<>(selected.tracks());
        Set<Long> pickedIds = preference.stream().map(TrackDTO::getId).collect(Collectors.toSet());
        fused.stream().filter(t -> !pickedIds.contains(t.getId())).map(TrackDTO::from).forEach(preference::add);

        EmotionQuotaAllocator.Allocation allocation =
                EmotionQuotaAllocator.allocate(preference, plan.distribution(), plan.ragaByEmotion(), topNResults);
        ensureExplained(allocation.tracks(), evidenceByTrack);
        log.info("EMOTION SPLIT: {}", allocation.slots());
        return new RankingResult(allocation.tracks(), selected.conversationalMessage(), allocation.slots(),
                selected.llmSelectionUsed());
    }

    /**
     * Filters every retrieval source down to language=Hindi tracks only. Applied before
     * fusion so the Hindi-only restriction is enforced at the earliest possible stage —
     * fuse/evidence/rerank/select downstream never see a non-Hindi track to begin with.
     */
    private Map<String, List<Track>> filterToHindi(Map<String, List<Track>> sources) {
        Map<String, List<Track>> filtered = new LinkedHashMap<>();
        sources.forEach((name, tracks) -> filtered.put(name,
                tracks.stream().filter(t -> "Hindi".equalsIgnoreCase(t.getLanguage())).toList()));
        return filtered;
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
    private Map<String, List<Track>> retrieve(EmotionMetadata emotion, String primaryRaga, PlaylistPlan plan) {
        Map<String, List<Track>> sources = new LinkedHashMap<>();

        String primaryEmotion = emotion.getPrimaryEmotionSafe();
        String language       = emotion.getLanguageSafe();

        // Source 0: the specific Raga chosen by RagaSelectionService (conversation pipeline
        // only) — highest-weighted source, since Raga is meant to drive selection here,
        // not just filter it.
        if (primaryRaga != null && !primaryRaga.isBlank()) {
            sources.put("raga_primary", trackRepository.findByRagaInOrderByPopularityDesc(List.of(primaryRaga)));
        }

        // Source 1: primary emotion + language match (strongest signal)
        sources.put("emotion_lang",
                trackRepository.findByPrimaryEmotionIgnoreCaseAndLanguageIgnoreCaseOrderByPopularityDesc(
                        primaryEmotion, language));

        // Source 2: primary emotion, any language
        sources.put("emotion_any",
                trackRepository.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc(primaryEmotion));

        // Sources: one primary-emotion source per other emotion in the Patient's response
        // distribution (conversation pipeline only), so every share of the playlist split
        // has primary-emotion candidates in the fused pool.
        if (plan.distribution() != null) {
            for (String e : plan.distribution().keySet()) {
                if (e.equalsIgnoreCase(primaryEmotion)) continue;
                sources.put("distribution_" + e.toUpperCase(),
                        trackRepository.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc(e));
            }
        }

        // Sources: one per share raga (conversation pipeline only) — each emotion's share has its own
        // raga, and its songs must be in the fused pool for that share to be filled from them.
        if (plan.ragaByEmotion() != null) {
            new LinkedHashSet<>(plan.ragaByEmotion().values()).stream()
                    .filter(r -> !r.equalsIgnoreCase(primaryRaga))
                    .forEach(r -> sources.put("share_raga_" + r,
                            trackRepository.findByRagaInOrderByPopularityDesc(List.of(r))));
        }

        // Source: ragas traditionally associated (via rasa theory) with the user's
        // primary + expanded emotions — e.g. STRESS/ANGER → Ahir Bhairav, Bhairav;
        // SADNESS/MELANCHOLY → Bhairavi, Darbari Kanada. See RagaRasaCatalog.
        Set<String> emotionsForRaga = new LinkedHashSet<>();
        emotionsForRaga.add(primaryEmotion);
        if (emotion.getSecondaryEmotions() != null) emotionsForRaga.addAll(emotion.getSecondaryEmotions());
        if (emotion.getExpandedEmotions() != null) emotionsForRaga.addAll(emotion.getExpandedEmotions());
        List<String> candidateRagas = RagaRasaCatalog.ragasForEmotions(emotionsForRaga);
        if (!candidateRagas.isEmpty()) {
            sources.put("raga_match", trackRepository.findByRagaInOrderByPopularityDesc(candidateRagas));
        }

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
        if (sourceName.equals("raga_primary")) return RAGA_PRIMARY_WEIGHT;
        Double explicit = SOURCE_WEIGHTS.get(sourceName);
        if (explicit != null) return explicit;
        if (sourceName.startsWith("distribution_")) return DISTRIBUTION_EMOTION_WEIGHT;
        if (sourceName.startsWith("share_raga_"))   return SOURCE_WEIGHTS.get("raga_match");
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

            log.debug("Groq selection raw response: {}", rawJson);
            return parseSelectResponse(rawJson, candidates, evidenceByTrack, emotion);

        } catch (Exception ex) {
            log.warn("LLM selection failed — falling back to RRF order. Cause: {}", ex.getMessage());
            return rrfFallback(candidates, evidenceByTrack, emotion);
        }
    }

    // ── Response Parsing ─────────────────────────────────────────

    private RankingResult parseSelectResponse(String rawJson,
                                               List<Track> candidates,
                                               Map<Long, List<EvidenceRecord>> evidenceByTrack,
                                               EmotionMetadata emotion) {
        try {
            String cleaned = extractJsonBlock(rawJson);
            JsonNode root  = objectMapper.readTree(cleaned);

            Map<Long, Track> trackMap = candidates.stream()
                    .collect(Collectors.toMap(Track::getId, t -> t));

            List<Long> selectedIds = new ArrayList<>();
            root.path("selected_track_ids").forEach(node -> selectedIds.add(node.asLong()));

            Map<String, String> explanations = parseExplanations(root.path("track_explanations"));

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

            int llmExplained = (int) ordered.stream().filter(t -> t.getExplanation() != null && !t.getExplanation().isBlank()).count();
            ensureExplained(ordered, evidenceByTrack);
            String message = root.path("recommendation_message").asText(defaultMessage(emotion));
            log.info("SELECT: Groq selected {} tracks ({} explained by the LLM, rest from their evidence)", ordered.size(), llmExplained);
            return new RankingResult(ordered, message, List.of(), true);

        } catch (Exception ex) {
            log.warn("Failed to parse rerank response: {}", ex.getMessage());
            return rrfFallback(candidates, evidenceByTrack, emotion);
        }
    }

    /** Fallback when LLM reranking fails — return candidates in RRF order */
    private RankingResult rrfFallback(List<Track> candidates, Map<Long, List<EvidenceRecord>> evidenceByTrack,
                                      EmotionMetadata emotion) {
        List<TrackDTO> top = candidates.stream()
                .limit(topNResults)
                .map(TrackDTO::from)
                .collect(Collectors.toList());
        ensureExplained(top, evidenceByTrack);
        return new RankingResult(top, defaultMessage(emotion), List.of(), false);
    }

    /**
     * Reads track_explanations in the shapes the LLM has been seen to return: an object keyed by
     * track id (keys like "12", "track_12" or "id 12"), or a list of {id|track_id, explanation|reason}.
     * Returns explanations keyed by the bare numeric id.
     */
    private Map<String, String> parseExplanations(JsonNode node) {
        Map<String, String> out = new HashMap<>();
        if (node == null || node.isMissingNode() || node.isNull()) return out;
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String id = e.getKey().replaceAll("\\D", "");
                if (!id.isEmpty() && e.getValue().isTextual()) out.put(id, e.getValue().asText());
            });
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String id = item.path("id").asText(item.path("track_id").asText("")).replaceAll("\\D", "");
                String text = item.path("explanation").asText(item.path("reason").asText(""));
                if (!id.isEmpty() && !text.isBlank()) out.put(id, text);
            }
        }
        return out;
    }

    /**
     * Req. 5 — every recommended song says why it was recommended. Songs without an LLM explanation
     * (the LLM skipped them, they were padded in, or the LLM step failed) get one assembled from
     * their own code-computed evidence, so nothing is invented.
     */
    private void ensureExplained(List<TrackDTO> tracks, Map<Long, List<EvidenceRecord>> evidenceByTrack) {
        for (TrackDTO t : tracks) {
            if (t.getExplanation() != null && !t.getExplanation().isBlank()) continue;
            List<EvidenceRecord> ev = evidenceByTrack.getOrDefault(t.getId(), List.of()).stream()
                    .sorted(java.util.Comparator.comparingDouble(EvidenceRecord::strength).reversed())
                    .limit(2)
                    .toList();
            t.setExplanation(ev.isEmpty()
                    ? "Included to complete the playlist as a well-rated Hindi " + (t.getGenre() == null ? "" : t.getGenre() + " ")
                      + "track; no direct emotional match was found for it."
                    : "Recommended because: " + ev.stream().map(EvidenceRecord::text).collect(Collectors.joining("; ")) + ".");
        }
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

    /**
     * @param emotionAllocation per-emotion share of the playlist; empty unless a distribution was given
     * @param llmSelectionUsed  false when the LLM SELECT call failed or its reply couldn't be parsed and
     *                          the songs are in fused-rank order instead (e.g. Groq rate limit)
     */
    public record RankingResult(List<TrackDTO> tracks, String conversationalMessage,
                                List<com.musicrec.dto.EmotionSlotDTO> emotionAllocation,
                                boolean llmSelectionUsed) {
        public RankingResult(List<TrackDTO> tracks, String conversationalMessage) {
            this(tracks, conversationalMessage, List.of(), true);
        }
    }
}
