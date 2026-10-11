package com.musicrec.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.Track;
import com.musicrec.repository.TrackRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Req. 5: every recommended song must say why it was recommended. Seen live: the LLM chose 10 songs
 * but returned its explanations in a shape the parser did not read, so all 10 came back unexplained;
 * songs from the RRF fallback were never explained at all.
 */
class MusicRecommendationServiceExplanationTest {

    private Track track(long id) {
        Track t = new Track();
        t.setId(id);
        t.setTitle("Track " + id);
        t.setArtist("Artist " + id);
        t.setGenre("Bollywood");
        t.setLanguage("Hindi");
        t.setPrimaryEmotion("JOY");
        t.setSecondaryEmotions(List.of());
        t.setMoodTags(List.of("happy"));
        t.setValence(BigDecimal.valueOf(0.8));
        t.setEnergyLevel(BigDecimal.valueOf(0.8));
        t.setPopularity(90 - (int) id);
        return t;
    }

    private final List<Track> pool = List.of(track(1), track(2), track(3), track(4), track(5));

    private MusicRecommendationService service(ChatClient chatClient) {
        TrackRepository repo = mock(TrackRepository.class);
        when(repo.findByPrimaryEmotionIgnoreCaseAndLanguageIgnoreCaseOrderByPopularityDesc(anyString(), anyString())).thenReturn(pool);
        when(repo.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc(anyString())).thenReturn(pool);
        when(repo.findByRagaInOrderByPopularityDesc(anyList())).thenReturn(List.of());
        when(repo.findByValenceAndEnergy(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(pool);
        when(repo.findAll()).thenReturn(pool);
        when(repo.findByLanguageIgnoreCaseOrderByPopularityDesc(anyString())).thenReturn(pool);

        LightweightRerankerService reranker = new LightweightRerankerService();
        ReflectionTestUtils.setField(reranker, "rerankPoolSize", 15);
        MusicRecommendationService s = new MusicRecommendationService(repo, new SemanticRetrievalService(repo),
                new EvidenceGroundingService(), reranker, chatClient, new ObjectMapper());
        ReflectionTestUtils.setField(s, "candidatePoolSize", 30);
        ReflectionTestUtils.setField(s, "topNResults", 10);
        return s;
    }

    private ChatClient replying(String json) {
        ChatClient c = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(c.prompt().user(anyString()).call().content()).thenReturn(json);
        return c;
    }

    private EmotionMetadata joy() {
        EmotionMetadata e = new EmotionMetadata();
        e.setPrimaryEmotion("JOY");
        e.setIntensity(0.7);
        e.setLanguage("English");
        e.setSecondaryEmotions(List.of());
        e.setExpandedEmotions(List.of());
        e.setMoodKeywords(List.of());
        return e;
    }

    private Map<Long, String> explanations(MusicRecommendationService.RankingResult r) {
        return r.tracks().stream().collect(Collectors.toMap(TrackDTO::getId, t -> t.getExplanation() == null ? "" : t.getExplanation()));
    }

    @Test
    void everySongIsExplainedEvenWhenLlmSelectionFails() {
        MusicRecommendationService.RankingResult r = service(mock(ChatClient.class)).recommend(joy());

        assertThat(r.tracks()).isNotEmpty();
        assertThat(r.tracks()).allSatisfy(t -> assertThat(t.getExplanation()).isNotBlank());
    }

    @Test
    void explanationsReturnedAsAListOfObjectsAreUsed() {
        String json = """
                {"selected_track_ids": [1, 2], "recommendation_message": "m",
                 "track_explanations": [{"id": 1, "explanation": "LLM reason one"}, {"track_id": "2", "reason": "LLM reason two"}]}
                """;
        Map<Long, String> e = explanations(service(replying(json)).recommend(joy()));

        assertThat(e.get(1L)).isEqualTo("LLM reason one");
        assertThat(e.get(2L)).isEqualTo("LLM reason two");
    }

    @Test
    void explanationsKeyedWithAPrefixAreUsed() {
        String json = """
                {"selected_track_ids": [1, 2], "recommendation_message": "m",
                 "track_explanations": {"track_1": "LLM reason one", "id 2": "LLM reason two"}}
                """;
        Map<Long, String> e = explanations(service(replying(json)).recommend(joy()));

        assertThat(e.get(1L)).isEqualTo("LLM reason one");
        assertThat(e.get(2L)).isEqualTo("LLM reason two");
    }

    @Test
    void songsTheLlmDidNotExplainGetAnEvidenceBasedExplanation() {
        String json = """
                {"selected_track_ids": [1, 2, 3], "recommendation_message": "m", "track_explanations": {"1": "LLM reason one"}}
                """;
        MusicRecommendationService.RankingResult r = service(replying(json)).recommend(joy());

        assertThat(explanations(r).get(1L)).isEqualTo("LLM reason one");
        assertThat(r.tracks()).allSatisfy(t -> assertThat(t.getExplanation()).isNotBlank());
    }
}
