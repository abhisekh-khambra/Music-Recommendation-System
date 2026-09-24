package com.musicrec.controller;

import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.RecommendationRequest;
import com.musicrec.dto.RecommendationResponse;
import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.ChatHistory;
import com.musicrec.entity.Track;
import com.musicrec.repository.ChatHistoryRepository;
import com.musicrec.repository.TrackRepository;
import com.musicrec.repository.UserRepository;
import com.musicrec.service.EmotionAnalysisService;
import com.musicrec.service.MusicRecommendationService;
import com.musicrec.service.SpeechToTextService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST Controller — exposes two recommendation endpoints:
 *   POST /api/v1/recommend/text   — text input
 *   POST /api/v1/recommend/voice  — audio file input (multipart)
 *   GET  /api/v1/history/{sessionId} — retrieve chat history for a session
 *   GET  /api/v1/tracks           — browse the full track catalogue
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class RecommendationController {

    private final EmotionAnalysisService emotionAnalysisService;
    private final MusicRecommendationService recommendationService;
    private final SpeechToTextService speechToTextService;
    private final ChatHistoryRepository chatHistoryRepository;
    private final UserRepository userRepository;
    private final TrackRepository trackRepository;

    // ── Text Recommendation ───────────────────────────────────────

    /**
     * Accepts free-form text (Hindi or English) and returns top-10 songs.
     *
     * Request body:
     * {
     *   "message":   "I'm feeling very stressed from work today",
     *   "sessionId": "optional-session-uuid",
     *   "userId":    null
     * }
     */
    @PostMapping("/recommend/text")
    public ResponseEntity<RecommendationResponse> recommendByText(
            @Valid @RequestBody RecommendationRequest request) {

        log.info("Text recommendation request: session={}", request.getSessionId());

        String sessionId = resolveSessionId(request.getSessionId());

        // 1. Emotion analysis
        EmotionMetadata emotion = emotionAnalysisService.analyze(request.getMessage());

        // 2. Retrieve → Fuse → Rerank
        MusicRecommendationService.RankingResult result = recommendationService.recommend(emotion);

        // 3. Persist to chat history
        persistHistory(request.getMessage(), emotion, result, sessionId,
                request.getUserId(), ChatHistory.InputType.text);

        // 4. Build and return response
        RecommendationResponse response = RecommendationResponse.builder()
                .userMessage(request.getMessage())
                .transcript(request.getMessage())
                .emotion(emotion)
                .recommendations(result.tracks())
                .conversationalMessage(result.conversationalMessage())
                .sessionId(sessionId)
                .timestamp(System.currentTimeMillis())
                .build();

        return ResponseEntity.ok(response);
    }

    // ── Voice Recommendation ──────────────────────────────────────

    /**
     * Accepts an audio file, transcribes it via Groq Whisper, then runs the
     * same emotion + recommendation pipeline as the text endpoint.
     *
     * Form params:
     *   audio     — the audio blob (WebM / WAV / MP3 / M4A)
     *   sessionId — optional
     *   userId    — optional
     */
    @PostMapping(value = "/recommend/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RecommendationResponse> recommendByVoice(
            @RequestPart("audio") MultipartFile audioFile,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "userId", required = false) Long userId) {

        log.info("Voice recommendation request: session={}, audioSize={}",
                sessionId, audioFile.getSize());

        if (audioFile.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        sessionId = resolveSessionId(sessionId);

        // 1. Speech-to-text
        String transcript = speechToTextService.transcribe(audioFile);

        // 2. Emotion analysis on transcript
        EmotionMetadata emotion = emotionAnalysisService.analyze(transcript);

        // 3. Retrieve → Fuse → Rerank
        MusicRecommendationService.RankingResult result = recommendationService.recommend(emotion);

        // 4. Persist
        persistHistory(transcript, emotion, result, sessionId, userId, ChatHistory.InputType.voice);

        // 5. Response
        RecommendationResponse response = RecommendationResponse.builder()
                .userMessage(transcript)
                .transcript(transcript)
                .emotion(emotion)
                .recommendations(result.tracks())
                .conversationalMessage(result.conversationalMessage())
                .sessionId(sessionId)
                .timestamp(System.currentTimeMillis())
                .build();

        return ResponseEntity.ok(response);
    }

    // ── Chat History ──────────────────────────────────────────────

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<List<ChatHistory>> getHistory(@PathVariable String sessionId) {
        List<ChatHistory> history = chatHistoryRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId);
        return ResponseEntity.ok(history);
    }

    // ── Track Catalogue ───────────────────────────────────────────

    /**
     * Returns ALL tracks in the database, sorted by popularity descending.
     *
     * Optional query parameters:
     *   ?emotion=JOY       — filter by primary emotion (case-insensitive)
     *   ?language=Hindi    — filter by language (case-insensitive)
     *
     * Examples:
     *   GET /api/v1/tracks
     *   GET /api/v1/tracks?emotion=SADNESS
     *   GET /api/v1/tracks?language=Hindi
     *   GET /api/v1/tracks?emotion=JOY&language=English
     */
    @GetMapping("/tracks")
    public ResponseEntity<List<TrackDTO>> getAllTracks(
            @RequestParam(value = "emotion",  required = false) String emotion,
            @RequestParam(value = "language", required = false) String language) {

        List<Track> tracks;

        if (emotion != null && language != null) {
            tracks = trackRepository
                    .findByPrimaryEmotionIgnoreCaseAndLanguageIgnoreCaseOrderByPopularityDesc(
                            emotion, language);
        } else if (emotion != null) {
            tracks = trackRepository
                    .findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc(emotion);
        } else if (language != null) {
            tracks = trackRepository
                    .findByLanguageIgnoreCaseOrderByPopularityDesc(language);
        } else {
            tracks = trackRepository
                    .findAllByOrderByPopularityDesc();
        }

        List<TrackDTO> dtos = tracks.stream().map(TrackDTO::from).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private String resolveSessionId(String provided) {
        return (provided != null && !provided.isBlank())
                ? provided
                : UUID.randomUUID().toString();
    }

    private void persistHistory(String userMessage,
                                 EmotionMetadata emotion,
                                 MusicRecommendationService.RankingResult result,
                                 String sessionId,
                                 Long userId,
                                 ChatHistory.InputType inputType) {
        try {
            ChatHistory history = new ChatHistory();
            history.setSessionId(sessionId);
            history.setUserMessage(userMessage);
            history.setDetectedEmotion(emotion.getPrimaryEmotionSafe());
            history.setEmotionIntensity(BigDecimal.valueOf(emotion.getIntensity()));
            history.setAssistantResponse(result.conversationalMessage());
            history.setRecommendedTrackIds(
                    result.tracks().stream().map(TrackDTO::getId).collect(Collectors.toList()));
            history.setInputType(inputType);
            history.setLanguage(emotion.getLanguageSafe());

            if (userId != null) {
                userRepository.findById(userId).ifPresent(history::setUser);
            } else {
                // Default anonymous user
                userRepository.findByUsername("anonymous").ifPresent(history::setUser);
            }

            chatHistoryRepository.save(history);
        } catch (Exception ex) {
            log.warn("Failed to persist chat history: {}", ex.getMessage());
        }
    }

}
