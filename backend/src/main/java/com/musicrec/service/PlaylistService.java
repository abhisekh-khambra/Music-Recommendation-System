package com.musicrec.service;

import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.ChatHistory;
import com.musicrec.entity.Track;
import com.musicrec.repository.ChatHistoryRepository;
import com.musicrec.repository.TrackRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Playlists beyond the current mood, the way a streaming app arranges music: from what this session was
 * recommended before, by artist, and by raga. Pure catalogue/history lookups — Hindi only like the rest
 * of the system, no LLM.
 */
@Service
public class PlaylistService {

    private static final int MAX_TRACKS = 20;
    private static final String HINDI = "Hindi";

    private final TrackRepository trackRepository;
    private final ChatHistoryRepository chatHistoryRepository;

    public PlaylistService(TrackRepository trackRepository, ChatHistoryRepository chatHistoryRepository) {
        this.trackRepository = trackRepository;
        this.chatHistoryRepository = chatHistoryRepository;
    }

    public record Playlist(String name, String description, List<TrackDTO> tracks) {}

    /** What the GUI can offer: every credited Hindi singer, and each raga with its number of Hindi songs. */
    public record Options(List<String> artists, Map<String, Integer> ragas) {}

    /** Songs recommended in this session, most often first, ties broken by most recent. */
    public Playlist fromHistory(String sessionId) {
        List<ChatHistory> turns = chatHistoryRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        Map<Long, Integer> count = new HashMap<>();
        Map<Long, Integer> lastSeen = new HashMap<>();
        for (int i = 0; i < turns.size(); i++) {
            List<Long> ids = turns.get(i).getRecommendedTrackIds();
            if (ids == null) continue;
            for (Long id : ids) {
                count.merge(id, 1, Integer::sum);
                lastSeen.put(id, i);
            }
        }
        if (count.isEmpty()) {
            return new Playlist("From your history", "No songs have been recommended in this session yet.", List.of());
        }
        Map<Long, Track> byId = trackRepository.findAllById(count.keySet()).stream()
                .filter(t -> HINDI.equalsIgnoreCase(t.getLanguage()))
                .collect(Collectors.toMap(Track::getId, t -> t));
        List<TrackDTO> out = count.keySet().stream()
                .filter(byId::containsKey)
                .sorted(Comparator.<Long>comparingInt(count::get).reversed()
                        .thenComparing(Comparator.<Long>comparingInt(lastSeen::get).reversed()))
                .limit(MAX_TRACKS)
                .map(id -> {
                    TrackDTO dto = TrackDTO.from(byId.get(id));
                    int n = count.get(id);
                    dto.setExplanation("Recommended to you " + n + (n == 1 ? " time" : " times") + " in this session.");
                    return dto;
                })
                .toList();
        return new Playlist("From your history", "Songs you were recommended in this session, most frequent first.", out);
    }

    /** Hindi songs crediting this singer (songs can credit several, comma-separated), most popular first. */
    public Playlist byArtist(String artist) {
        String wanted = artist.strip().toLowerCase();
        List<TrackDTO> out = hindiTracks().stream()
                .filter(t -> splitArtists(t.getArtist()).stream().anyMatch(a -> a.equalsIgnoreCase(wanted)))
                .limit(MAX_TRACKS)
                .map(TrackDTO::from)
                .toList();
        return new Playlist(artist, "Hindi songs by " + artist + ", most popular first.", out);
    }

    public Playlist byRaga(String raga) {
        List<TrackDTO> out = trackRepository.findByRagaInAndLanguageIgnoreCaseOrderByPopularityDesc(List.of(raga), HINDI)
                .stream().limit(MAX_TRACKS).map(TrackDTO::from).toList();
        return new Playlist("Raga " + raga, "Hindi songs set in Raga " + raga + ".", out);
    }

    public Options options() {
        List<Track> hindi = hindiTracks();
        List<String> artists = hindi.stream().flatMap(t -> splitArtists(t.getArtist()).stream())
                .distinct().sorted().toList();
        Map<String, Integer> ragas = new TreeMap<>();
        hindi.stream().map(Track::getRaga).filter(r -> r != null && !r.isBlank()).forEach(r -> ragas.merge(r, 1, Integer::sum));
        return new Options(artists, ragas);
    }

    private List<Track> hindiTracks() {
        return trackRepository.findByLanguageIgnoreCaseOrderByPopularityDesc(HINDI);
    }

    private static List<String> splitArtists(String artist) {
        if (artist == null) return List.of();
        return Arrays.stream(artist.split(",|&| and ")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
