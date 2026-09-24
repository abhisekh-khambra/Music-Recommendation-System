package com.musicrec.controller;

import com.musicrec.repository.TrackRepository;
import com.musicrec.service.SpeechToTextService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quick health-check endpoint.
 * GET /api/v1/health  → tells you if the backend is alive and what's ready.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    private final TrackRepository trackRepository;
    private final SpeechToTextService speechToTextService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", "MoodTunes Music Recommendation API");

        try {
            long trackCount = trackRepository.count();
            status.put("database", "CONNECTED");
            status.put("tracksLoaded", trackCount);
        } catch (Exception e) {
            status.put("database", "ERROR: " + e.getMessage());
        }

        status.put("voiceInput",
                speechToTextService.isTranscriptionAvailable() ? "AVAILABLE" : "UNAVAILABLE");

        return ResponseEntity.ok(status);
    }
}
