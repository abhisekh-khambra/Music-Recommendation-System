package com.musicrec.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.EmotionSlotDTO;
import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.Track;
import com.musicrec.repository.TrackRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The conversation pipeline's Top-10 is split across the Patient's emotions by share: a
 * 60% SADNESS / 40% NOSTALGIA conversation gets 6 SADNESS and 4 NOSTALGIA songs, even though
 * the overall (dominant) emotion alone would fill every slot with SADNESS songs.
 */
class MusicRecommendationServiceEmotionSplitTest {

    private Track track(long id, String emotion, int popularity) {
        Track t = new Track();
        t.setId(id);
        t.setTitle("Track " + id);
        t.setArtist("Artist " + id);
        t.setLanguage("Hindi");
        t.setPrimaryEmotion(emotion);
        t.setSecondaryEmotions(List.of());
        t.setMoodTags(List.of());
        t.setValence(BigDecimal.valueOf(0.3));
        t.setEnergyLevel(BigDecimal.valueOf(0.3));
        t.setPopularity(popularity);
        return t;
    }

    @Test
    void topTenIsSplitByTheShareOfEachEmotionInThePatientsResponses() {
        List<Track> sad = new ArrayList<>();
        for (long i = 1; i <= 12; i++) sad.add(track(i, "SADNESS", 100 - (int) i));
        List<Track> nostalgic = new ArrayList<>();
        for (long i = 21; i <= 26; i++) nostalgic.add(track(i, "NOSTALGIA", 60 - (int) i));

        TrackRepository repo = mock(TrackRepository.class);
        when(repo.findByPrimaryEmotionIgnoreCaseAndLanguageIgnoreCaseOrderByPopularityDesc(anyString(), anyString()))
                .thenReturn(sad);
        when(repo.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc("SADNESS")).thenReturn(sad);
        when(repo.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc("NOSTALGIA")).thenReturn(nostalgic);
        when(repo.findByRagaInOrderByPopularityDesc(anyList())).thenReturn(List.of());
        when(repo.findByValenceAndEnergy(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(sad);
        when(repo.findAll()).thenReturn(sad);

        LightweightRerankerService reranker = new LightweightRerankerService();
        ReflectionTestUtils.setField(reranker, "rerankPoolSize", 15);
        MusicRecommendationService service = new MusicRecommendationService(
                repo, new SemanticRetrievalService(repo), new EvidenceGroundingService(),
                reranker, mock(ChatClient.class), new ObjectMapper());
        ReflectionTestUtils.setField(service, "candidatePoolSize", 30);
        ReflectionTestUtils.setField(service, "topNResults", 10);

        EmotionMetadata emotion = new EmotionMetadata();
        emotion.setPrimaryEmotion("SADNESS");
        emotion.setIntensity(0.6);
        emotion.setLanguage("Hindi");
        emotion.setSecondaryEmotions(List.of("NOSTALGIA"));
        emotion.setExpandedEmotions(List.of());
        emotion.setMoodKeywords(List.of());

        Map<String, Double> distribution = new LinkedHashMap<>();
        distribution.put("SADNESS", 60.0);
        distribution.put("NOSTALGIA", 40.0);

        MusicRecommendationService.RankingResult result = service.recommend(emotion, null, true,
                new MusicRecommendationService.PlaylistPlan(distribution, Map.of()));

        assertThat(result.tracks()).hasSize(10);
        assertThat(result.tracks()).filteredOn(t -> "SADNESS".equals(t.getPrimaryEmotion())).hasSize(6);
        assertThat(result.tracks()).filteredOn(t -> "NOSTALGIA".equals(t.getPrimaryEmotion())).hasSize(4);
        assertThat(result.tracks()).allSatisfy(t -> assertThat(t.getExplanation()).isNotBlank());
        assertThat(result.tracks()).extracting(TrackDTO::getMatchedEmotion).doesNotContainNull();
        assertThat(result.emotionAllocation()).containsExactly(
                new EmotionSlotDTO("SADNESS", 60.0, 6, 6),
                new EmotionSlotDTO("NOSTALGIA", 40.0, 4, 4));
    }

    @Test
    void eachSharesRagaIsRetrievedAndPreferredWithinThatShare() {
        // SADNESS share → Darbari Kanada, NOSTALGIA share → Yaman. The raga songs are the LEAST popular,
        // so they only reach the playlist if the per-share raga actually drives retrieval and allocation.
        List<Track> sad = new ArrayList<>();
        for (long i = 1; i <= 8; i++) sad.add(track(i, "SADNESS", 100 - (int) i));
        Track sadDarbari = track(9, "SADNESS", 5);
        sadDarbari.setRaga("Darbari Kanada");
        List<Track> nostalgic = new ArrayList<>();
        for (long i = 21; i <= 24; i++) nostalgic.add(track(i, "NOSTALGIA", 60 - (int) i));
        Track nostalgicYaman = track(25, "NOSTALGIA", 4);
        nostalgicYaman.setRaga("Yaman");

        TrackRepository repo = mock(TrackRepository.class);
        when(repo.findByPrimaryEmotionIgnoreCaseAndLanguageIgnoreCaseOrderByPopularityDesc(anyString(), anyString())).thenReturn(sad);
        when(repo.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc("SADNESS")).thenReturn(sad);
        when(repo.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc("NOSTALGIA")).thenReturn(nostalgic);
        when(repo.findByRagaInOrderByPopularityDesc(anyList())).thenReturn(List.of());
        when(repo.findByRagaInOrderByPopularityDesc(List.of("Darbari Kanada"))).thenReturn(List.of(sadDarbari));
        when(repo.findByRagaInOrderByPopularityDesc(List.of("Yaman"))).thenReturn(List.of(nostalgicYaman));
        when(repo.findByValenceAndEnergy(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(sad);
        when(repo.findAll()).thenReturn(sad);

        LightweightRerankerService reranker = new LightweightRerankerService();
        ReflectionTestUtils.setField(reranker, "rerankPoolSize", 15);
        MusicRecommendationService service = new MusicRecommendationService(
                repo, new SemanticRetrievalService(repo), new EvidenceGroundingService(),
                reranker, mock(ChatClient.class), new ObjectMapper());
        ReflectionTestUtils.setField(service, "candidatePoolSize", 30);
        ReflectionTestUtils.setField(service, "topNResults", 4);

        EmotionMetadata emotion = new EmotionMetadata();
        emotion.setPrimaryEmotion("SADNESS");
        emotion.setIntensity(0.6);
        emotion.setLanguage("Hindi");
        emotion.setSecondaryEmotions(List.of());
        emotion.setExpandedEmotions(List.of());
        emotion.setMoodKeywords(List.of());

        Map<String, Double> distribution = new LinkedHashMap<>();
        distribution.put("SADNESS", 50.0);
        distribution.put("NOSTALGIA", 50.0);
        Map<String, String> ragas = new LinkedHashMap<>();
        ragas.put("SADNESS", "Darbari Kanada");
        ragas.put("NOSTALGIA", "Yaman");

        MusicRecommendationService.RankingResult result = service.recommend(emotion, null, true,
                new MusicRecommendationService.PlaylistPlan(distribution, ragas));

        assertThat(result.tracks()).extracting(TrackDTO::getId).contains(9L, 25L);
        assertThat(result.tracks().get(0).getId()).isEqualTo(9L);   // SADNESS share leads with its raga song
        assertThat(result.emotionAllocation()).containsExactly(
                new EmotionSlotDTO("SADNESS", 50.0, 2, 2, "Darbari Kanada"),
                new EmotionSlotDTO("NOSTALGIA", 50.0, 2, 2, "Yaman"));
    }
}
