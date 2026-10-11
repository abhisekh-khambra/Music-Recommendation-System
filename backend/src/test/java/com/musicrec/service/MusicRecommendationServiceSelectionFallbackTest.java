package com.musicrec.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.entity.Track;
import com.musicrec.repository.TrackRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The result must say whether the LLM actually chose the songs. Seen in an evaluation run: the
 * SELECT call hit Groq's daily token limit (HTTP 429), the songs silently came back in fused-rank
 * order, and the evaluation report could not tell those cases apart from real LLM selections.
 */
class MusicRecommendationServiceSelectionFallbackTest {

    private Track track(long id) {
        Track t = new Track();
        t.setId(id);
        t.setTitle("Track " + id);
        t.setArtist("Artist " + id);
        t.setLanguage("Hindi");
        t.setPrimaryEmotion("JOY");
        t.setSecondaryEmotions(List.of());
        t.setMoodTags(List.of());
        t.setValence(BigDecimal.valueOf(0.8));
        t.setEnergyLevel(BigDecimal.valueOf(0.8));
        t.setPopularity(90 - (int) id);
        return t;
    }

    private MusicRecommendationService service(ChatClient chatClient) {
        List<Track> pool = List.of(track(1), track(2), track(3));
        TrackRepository repo = mock(TrackRepository.class);
        when(repo.findByPrimaryEmotionIgnoreCaseAndLanguageIgnoreCaseOrderByPopularityDesc(anyString(), anyString())).thenReturn(pool);
        when(repo.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc(anyString())).thenReturn(pool);
        when(repo.findByRagaInOrderByPopularityDesc(anyList())).thenReturn(List.of());
        when(repo.findByValenceAndEnergy(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(pool);
        when(repo.findAll()).thenReturn(pool);

        LightweightRerankerService reranker = new LightweightRerankerService();
        ReflectionTestUtils.setField(reranker, "rerankPoolSize", 15);
        MusicRecommendationService s = new MusicRecommendationService(repo, new SemanticRetrievalService(repo),
                new EvidenceGroundingService(), reranker, chatClient, new ObjectMapper());
        ReflectionTestUtils.setField(s, "candidatePoolSize", 30);
        ReflectionTestUtils.setField(s, "topNResults", 10);
        return s;
    }

    private EmotionMetadata joy() {
        EmotionMetadata e = new EmotionMetadata();
        e.setPrimaryEmotion("JOY");
        e.setIntensity(0.7);
        e.setLanguage("Hindi");
        e.setSecondaryEmotions(List.of());
        e.setExpandedEmotions(List.of());
        e.setMoodKeywords(List.of());
        return e;
    }

    @Test
    void rateLimitedSelectionIsReportedAsNotLlmSelected() {
        ChatClient rateLimited = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(rateLimited.prompt().user(anyString()).call().content())
                .thenThrow(new RuntimeException("HTTP 429 - Rate limit reached ... tokens per day (TPD)"));

        MusicRecommendationService.RankingResult result = service(rateLimited).recommend(joy());

        assertThat(result.tracks()).isNotEmpty();
        assertThat(result.llmSelectionUsed()).isFalse();
    }

    @Test
    void unparseableSelectionIsReportedAsNotLlmSelected() {
        ChatClient garbage = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(garbage.prompt().user(anyString()).call().content()).thenReturn("not json at all");

        assertThat(service(garbage).recommend(joy()).llmSelectionUsed()).isFalse();
    }

    @Test
    void successfulSelectionIsReportedAsLlmSelected() {
        ChatClient ok = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(ok.prompt().user(anyString()).call().content()).thenReturn("""
                {"selected_track_ids": [2, 1, 3],
                 "recommendation_message": "Enjoy.",
                 "track_explanations": {"1": "a", "2": "b", "3": "c"}}""");

        MusicRecommendationService.RankingResult result = service(ok).recommend(joy());

        assertThat(result.llmSelectionUsed()).isTrue();
        assertThat(result.tracks()).extracting(t -> t.getId()).startsWith(2L);
    }

    @Test
    void emotionShareSplitKeepsTheSelectionFlag() {
        ChatClient rateLimited = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(rateLimited.prompt().user(anyString()).call().content()).thenThrow(new RuntimeException("HTTP 429"));

        MusicRecommendationService.RankingResult result = service(rateLimited)
                .recommend(joy(), null, true,
                        new MusicRecommendationService.PlaylistPlan(java.util.Map.of("JOY", 100.0), java.util.Map.of()));

        assertThat(result.llmSelectionUsed()).isFalse();
    }
}
