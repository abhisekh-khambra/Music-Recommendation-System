package com.musicrec.controller;

import com.musicrec.service.PlaylistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Playlists beyond the current mood (PlaylistService):
 *   GET /api/v1/playlists/options              singers and ragas available
 *   GET /api/v1/playlists/history/{sessionId}  songs recommended in this session
 *   GET /api/v1/playlists/artist?name=...      songs by a singer
 *   GET /api/v1/playlists/raga/{raga}          songs in a raga
 */
@RestController
@RequestMapping("/api/v1/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistService playlistService;

    @GetMapping("/options")
    public ResponseEntity<PlaylistService.Options> options() {
        return ResponseEntity.ok(playlistService.options());
    }

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<PlaylistService.Playlist> history(@PathVariable String sessionId) {
        return ResponseEntity.ok(playlistService.fromHistory(sessionId));
    }

    @GetMapping("/artist")
    public ResponseEntity<PlaylistService.Playlist> artist(@RequestParam("name") String name) {
        return ResponseEntity.ok(playlistService.byArtist(name));
    }

    @GetMapping("/raga/{raga}")
    public ResponseEntity<PlaylistService.Playlist> raga(@PathVariable String raga) {
        return ResponseEntity.ok(playlistService.byRaga(raga));
    }
}
