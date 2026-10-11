package com.musicrec.service;

import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.ChatHistory;
import com.musicrec.entity.Track;
import com.musicrec.repository.ChatHistoryRepository;
import com.musicrec.repository.TrackRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Meeting: "like Spotify… playlist generation… by artist… toggling in the GUI" — playlists beyond the current mood. */
class PlaylistServiceTest {

    private final TrackRepository tracks = mock(TrackRepository.class);
    private final ChatHistoryRepository history = mock(ChatHistoryRepository.class);
    private final PlaylistService service = new PlaylistService(tracks, history);

    private Track track(long id, String artist, String raga, String language, int popularity) {
        Track t = new Track();
        t.setId(id);
        t.setTitle("Song " + id);
        t.setArtist(artist);
        t.setRaga(raga);
        t.setLanguage(language);
        t.setPopularity(popularity);
        return t;
    }

    private ChatHistory turn(Long... ids) {
        ChatHistory h = new ChatHistory();
        h.setRecommendedTrackIds(List.of(ids));
        return h;
    }

    @Test
    void historyPlaylistRanksSongsByHowOftenTheyWereRecommendedThenRecency() {
        when(history.findBySessionIdOrderByCreatedAtAsc("s1")).thenReturn(List.of(turn(1L, 2L), turn(2L, 3L), turn(3L, 2L)));
        when(tracks.findAllById(anyIterable())).thenReturn(List.of(
                track(1, "A", null, "Hindi", 90), track(2, "B", null, "Hindi", 10), track(3, "C", null, "Hindi", 50)));

        PlaylistService.Playlist p = service.fromHistory("s1");

        assertThat(p.tracks()).extracting(TrackDTO::getId).containsExactly(2L, 3L, 1L);
        assertThat(p.tracks().get(0).getExplanation()).contains("3 times");
    }

    @Test
    void historyPlaylistIsEmptyForANewSession() {
        when(history.findBySessionIdOrderByCreatedAtAsc("new")).thenReturn(List.of());
        assertThat(service.fromHistory("new").tracks()).isEmpty();
    }

    @Test
    void artistPlaylistMatchesAnyCreditedSingerAndIsHindiOnly() {
        when(tracks.findByLanguageIgnoreCaseOrderByPopularityDesc("Hindi")).thenReturn(List.of(
                track(1, "Lata Mangeshkar", null, "Hindi", 90),
                track(2, "Lata Mangeshkar, Manna Dey", null, "Hindi", 80),
                track(3, "Mohammed Rafi", null, "Hindi", 70)));

        assertThat(service.byArtist("lata mangeshkar").tracks()).extracting(TrackDTO::getId).containsExactly(1L, 2L);
    }

    @Test
    void ragaPlaylistListsHindiSongsInThatRaga() {
        when(tracks.findByRagaInAndLanguageIgnoreCaseOrderByPopularityDesc(List.of("Yaman"), "Hindi"))
                .thenReturn(List.of(track(5, "X", "Yaman", "Hindi", 60)));

        PlaylistService.Playlist p = service.byRaga("Yaman");

        assertThat(p.tracks()).extracting(TrackDTO::getId).containsExactly(5L);
        assertThat(p.description()).contains("Yaman");
    }

    @Test
    void optionsListEachSingerAndEachRagaWithHindiSongs() {
        when(tracks.findByLanguageIgnoreCaseOrderByPopularityDesc("Hindi")).thenReturn(List.of(
                track(1, "Lata Mangeshkar", "Yaman", "Hindi", 90),
                track(2, "Lata Mangeshkar, Manna Dey", "Bhoopali", "Hindi", 80),
                track(3, "Arijit Singh", null, "Hindi", 70)));

        PlaylistService.Options o = service.options();

        assertThat(o.artists()).containsExactly("Arijit Singh", "Lata Mangeshkar", "Manna Dey");
        assertThat(o.ragas()).containsEntry("Yaman", 1).containsEntry("Bhoopali", 1).hasSize(2);
    }
}
