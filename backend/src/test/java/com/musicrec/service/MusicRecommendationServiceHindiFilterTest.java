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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies req. #5 (Hindi-only restriction) is enforced end-to-end through
 * MusicRecommendationService's retrieve → fuse → evidence → rerank → select
 * pipeline: when restrictToHindi=true, no non-Hindi track can reach the final
 * result, regardless of how many non-Hindi tracks the underlying retrieval
 * sources return.
 */
class MusicRecommendationServiceHindiFilterTest {

    private Track track(long id, String language, String emotion, int popularity) {
        Track t = new Track();
        t.setId(id);
        t.setTitle("Track " + id);
        t.setArtist("Artist " + id);
        t.setLanguage(language);
        t.setPrimaryEmotion(emotion);
        t.setSecondaryEmotions(List.of());
        t.setMoodTags(List.of());
        t.setValence(BigDecimal.valueOf(0.8));
        t.setEnergyLevel(BigDecimal.valueOf(0.8));
        t.setPopularity(popularity);
        return t;
    }

    private MusicRecommendationService newService(TrackRepository trackRepository) {
        SemanticRetrievalService semanticRetrievalService = new SemanticRetrievalService(trackRepository);
        EvidenceGroundingService evidenceGroundingService = new EvidenceGroundingService();
        LightweightRerankerService lightweightRerankerService = new LightweightRerankerService();
        ReflectionTestUtils.setField(lightweightRerankerService, "rerankPoolSize", 15);

        MusicRecommendationService service = new MusicRecommendationService(
                trackRepository, semanticRetrievalService, evidenceGroundingService,
                lightweightRerankerService, mock(ChatClient.class), new ObjectMapper());
        ReflectionTestUtils.setField(service, "candidatePoolSize", 30);
        ReflectionTestUtils.setField(service, "topNResults", 10);
        return service;
    }

    private EmotionMetadata joyEmotion() {
        EmotionMetadata e = new EmotionMetadata();
        e.setPrimaryEmotion("JOY");
        e.setIntensity(0.7);
        e.setLanguage("English");
        e.setSecondaryEmotions(List.of());
        e.setExpandedEmotions(List.of());
        e.setMoodKeywords(List.of());
        return e;
    }

    @Test
    void hindiOnlyModeNeverReturnsANonHindiTrackEvenWhenMostCandidatesAreNonHindi() {
        // 5 English tracks vs. 2 Hindi tracks — English dominates the raw retrieval pool.
        List<Track> mixedPool = List.of(
                track(1L, "English", "JOY", 95),
                track(2L, "English", "JOY", 94),
                track(3L, "English", "JOY", 93),
                track(4L, "English", "JOY", 92),
                track(5L, "English", "JOY", 91),
                track(6L, "Hindi", "JOY", 80),
                track(7L, "Hindi", "JOY", 79));

        TrackRepository trackRepository = mock(TrackRepository.class);
        when(trackRepository.findByPrimaryEmotionIgnoreCaseAndLanguageIgnoreCaseOrderByPopularityDesc(anyString(), anyString()))
                .thenReturn(mixedPool);
        when(trackRepository.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc(anyString()))
                .thenReturn(mixedPool);
        when(trackRepository.findByRagaInOrderByPopularityDesc(anyList()))
                .thenReturn(List.of());
        when(trackRepository.findByValenceAndEnergy(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(mixedPool);
        when(trackRepository.findAll()).thenReturn(mixedPool);
        when(trackRepository.findByLanguageIgnoreCaseOrderByPopularityDesc(anyString()))
                .thenReturn(mixedPool.stream().filter(t -> "Hindi".equalsIgnoreCase(t.getLanguage())).toList());

        MusicRecommendationService service = newService(trackRepository);

        MusicRecommendationService.RankingResult result = service.recommend(joyEmotion(), null, true);

        assertThat(result.tracks()).isNotEmpty();
        assertThat(result.tracks()).allSatisfy(t ->
                assertThat(t.getLanguage()).isEqualToIgnoringCase("Hindi"));
        // Only 2 Hindi tracks existed in the pool — can't have invented a 3rd to reach 10.
        assertThat(result.tracks()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void oneArgOverloadIsHindiOnlyByDefaultSystemWide() {
        // The restriction is now global: even the plain recommend(emotion) overload used by
        // /recommend/text and /recommend/voice must never leak a non-Hindi track.
        List<Track> mixedPool = List.of(
                track(1L, "English", "JOY", 95),
                track(2L, "Hindi", "JOY", 80));

        TrackRepository trackRepository = mock(TrackRepository.class);
        when(trackRepository.findByPrimaryEmotionIgnoreCaseAndLanguageIgnoreCaseOrderByPopularityDesc(anyString(), anyString()))
                .thenReturn(mixedPool);
        when(trackRepository.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc(anyString()))
                .thenReturn(mixedPool);
        when(trackRepository.findByRagaInOrderByPopularityDesc(anyList())).thenReturn(List.of());
        when(trackRepository.findByValenceAndEnergy(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(mixedPool);
        when(trackRepository.findAll()).thenReturn(mixedPool);
        when(trackRepository.findByLanguageIgnoreCaseOrderByPopularityDesc(anyString()))
                .thenReturn(mixedPool.stream().filter(t -> "Hindi".equalsIgnoreCase(t.getLanguage())).toList());

        MusicRecommendationService service = newService(trackRepository);

        MusicRecommendationService.RankingResult result = service.recommend(joyEmotion());

        List<String> languages = result.tracks().stream().map(TrackDTO::getLanguage).toList();
        assertThat(languages).doesNotContain("English");
        assertThat(result.tracks()).allSatisfy(t -> assertThat(t.getLanguage()).isEqualToIgnoringCase("Hindi"));
    }

    @Test
    void unknownOrNullLanguageTracksAreExcludedNotAssumedHindi() {
        // A track with no language metadata at all must never be treated as Hindi-eligible —
        // req. #2: "exclude tracks with unknown language until verified."
        List<Track> mixedPool = List.of(
                track(1L, null, "JOY", 95),
                track(2L, "Hindi", "JOY", 80));

        TrackRepository trackRepository = mock(TrackRepository.class);
        when(trackRepository.findByPrimaryEmotionIgnoreCaseAndLanguageIgnoreCaseOrderByPopularityDesc(anyString(), anyString()))
                .thenReturn(mixedPool);
        when(trackRepository.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc(anyString()))
                .thenReturn(mixedPool);
        when(trackRepository.findByRagaInOrderByPopularityDesc(anyList())).thenReturn(List.of());
        when(trackRepository.findByValenceAndEnergy(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(mixedPool);
        when(trackRepository.findAll()).thenReturn(mixedPool);
        when(trackRepository.findByLanguageIgnoreCaseOrderByPopularityDesc(anyString()))
                .thenReturn(mixedPool.stream().filter(t -> "Hindi".equalsIgnoreCase(t.getLanguage())).toList());

        MusicRecommendationService service = newService(trackRepository);

        MusicRecommendationService.RankingResult result = service.recommend(joyEmotion(), null, true);

        assertThat(result.tracks()).extracting(TrackDTO::getId).doesNotContain(1L);
        assertThat(result.tracks()).allSatisfy(t -> assertThat(t.getLanguage()).isEqualToIgnoringCase("Hindi"));
    }

    @Test
    void anyNonExactHindiLabelIsRejectedIncludingMixedOrAmbiguousTags() {
        // The filter is an exact, case-insensitive match on language=="Hindi" — a track tagged
        // "Hinglish", "Hindi-English", or any other non-exact label is rejected by construction,
        // never silently treated as eligible. req. #2: reject mixed-language-labeled tracks.
        List<Track> mixedPool = List.of(
                track(1L, "Hinglish", "JOY", 95),
                track(2L, "Hindi-English", "JOY", 94),
                track(3L, "English", "JOY", 93),
                track(4L, "Hindi", "JOY", 80));

        TrackRepository trackRepository = mock(TrackRepository.class);
        when(trackRepository.findByPrimaryEmotionIgnoreCaseAndLanguageIgnoreCaseOrderByPopularityDesc(anyString(), anyString()))
                .thenReturn(mixedPool);
        when(trackRepository.findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc(anyString()))
                .thenReturn(mixedPool);
        when(trackRepository.findByRagaInOrderByPopularityDesc(anyList())).thenReturn(List.of());
        when(trackRepository.findByValenceAndEnergy(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(mixedPool);
        when(trackRepository.findAll()).thenReturn(mixedPool);
        when(trackRepository.findByLanguageIgnoreCaseOrderByPopularityDesc(anyString()))
                .thenReturn(mixedPool.stream().filter(t -> "Hindi".equalsIgnoreCase(t.getLanguage())).toList());

        MusicRecommendationService service = newService(trackRepository);

        MusicRecommendationService.RankingResult result = service.recommend(joyEmotion(), null, true);

        assertThat(result.tracks()).extracting(TrackDTO::getId).containsOnly(4L);
    }
}
