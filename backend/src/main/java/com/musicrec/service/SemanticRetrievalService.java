package com.musicrec.service;

import com.musicrec.dto.EmotionMetadata;
import com.musicrec.entity.Track;
import com.musicrec.repository.TrackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Dense-style retrieval source for the hybrid lexical-dense pool.
 *
 * The other TrackRepository queries are exact/range matches on structured
 * fields (lexical). This source instead ranks the whole catalogue by TF-IDF
 * cosine similarity between the user's expanded emotional profile and each
 * track's text profile, so it can surface tracks the exact-match sources miss
 * (e.g. a track whose mood_tags overlap the user's mood only partially).
 *
 * No external embedding API is used — Groq does not currently expose one —
 * so this is a from-scratch TF-IDF vector space model. It runs over the full
 * catalogue on every call, which is cheap at this catalogue size.
 */
@Service
@Slf4j
public class SemanticRetrievalService {

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been",
            "to", "of", "in", "on", "for", "and", "or", "with", "that",
            "this", "it", "as", "at", "by", "from", "i", "you", "feel",
            "feeling", "user"
    );

    private final TrackRepository trackRepository;

    public SemanticRetrievalService(TrackRepository trackRepository) {
        this.trackRepository = trackRepository;
    }

    /** Returns the catalogue ranked by cosine similarity, highest first, capped at limit. */
    public List<Track> retrieveBySimilarity(EmotionMetadata emotion, int limit) {
        List<Track> allTracks = trackRepository.findAll();
        if (allTracks.isEmpty()) {
            return List.of();
        }

        Map<Track, List<String>> tokensByTrack = new LinkedHashMap<>();
        for (Track t : allTracks) {
            tokensByTrack.put(t, tokenize(trackText(t)));
        }

        Map<String, Double> idf = computeIdf(tokensByTrack.values());
        Map<String, Double> queryVector = tfIdfVector(tokenize(queryText(emotion)), idf);

        List<Track> ranked = tokensByTrack.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), cosineSimilarity(queryVector, tfIdfVector(e.getValue(), idf))))
                .filter(e -> e.getValue() > 0.0)
                .sorted(Map.Entry.<Track, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();

        log.debug("SEMANTIC_DENSE: {} tracks matched (cosine > 0) out of {}", ranked.size(), allTracks.size());
        return ranked;
    }

    // ── Text extraction ─────────────────────────────────────────────

    private String trackText(Track t) {
        StringBuilder sb = new StringBuilder();
        appendSafe(sb, t.getTitle());
        appendSafe(sb, t.getArtist());
        appendSafe(sb, t.getGenre());
        appendSafe(sb, t.getPrimaryEmotion());
        appendAllSafe(sb, t.getSecondaryEmotions());
        appendAllSafe(sb, t.getMoodTags());
        return sb.toString();
    }

    private String queryText(EmotionMetadata emotion) {
        StringBuilder sb = new StringBuilder();
        appendSafe(sb, emotion.getPrimaryEmotion());
        appendAllSafe(sb, emotion.getSecondaryEmotions());
        appendAllSafe(sb, emotion.getExpandedEmotions());
        appendAllSafe(sb, emotion.getMoodKeywords());
        appendAllSafe(sb, emotion.getExpandedMoodTags());
        appendSafe(sb, emotion.getContextSummary());
        return sb.toString();
    }

    private void appendSafe(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) sb.append(' ').append(value);
    }

    private void appendAllSafe(StringBuilder sb, List<String> values) {
        if (values != null) values.forEach(v -> appendSafe(sb, v));
    }

    // ── TF-IDF vector space model ───────────────────────────────────

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(tok -> tok.length() > 1 && !STOPWORDS.contains(tok))
                .toList();
    }

    private Map<String, Double> computeIdf(Collection<List<String>> docs) {
        int docCount = docs.size();
        Map<String, Integer> docFrequency = new HashMap<>();
        for (List<String> doc : docs) {
            new HashSet<>(doc).forEach(term -> docFrequency.merge(term, 1, Integer::sum));
        }
        Map<String, Double> idf = new HashMap<>();
        docFrequency.forEach((term, df) -> idf.put(term, Math.log((double) docCount / (1 + df)) + 1));
        return idf;
    }

    private Map<String, Double> tfIdfVector(List<String> tokens, Map<String, Double> idf) {
        if (tokens.isEmpty()) return Map.of();
        Map<String, Long> termFrequency = new HashMap<>();
        tokens.forEach(t -> termFrequency.merge(t, 1L, Long::sum));

        Map<String, Double> vector = new HashMap<>();
        termFrequency.forEach((term, tf) -> vector.put(term, tf * idf.getOrDefault(term, 0.0)));
        return vector;
    }

    private double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        Map<String, Double> smaller = a.size() <= b.size() ? a : b;
        Map<String, Double> larger  = a.size() <= b.size() ? b : a;

        double dotProduct = 0.0;
        for (var entry : smaller.entrySet()) {
            Double otherWeight = larger.get(entry.getKey());
            if (otherWeight != null) dotProduct += entry.getValue() * otherWeight;
        }

        double normA = Math.sqrt(a.values().stream().mapToDouble(v -> v * v).sum());
        double normB = Math.sqrt(b.values().stream().mapToDouble(v -> v * v).sum());
        if (normA == 0.0 || normB == 0.0) return 0.0;

        return dotProduct / (normA * normB);
    }
}
