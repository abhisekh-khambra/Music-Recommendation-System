package com.musicrec.controller;

import com.musicrec.audio.AcousticEmotionAnalysisService;
import com.musicrec.audio.AcousticFeatureExtractor;
import com.musicrec.audio.AudioTranscodingService;
import com.musicrec.audio.SpeechEmotionClient;
import com.musicrec.audio.SpeechEmotionFusion;
import com.musicrec.dto.AudioEmotionProfileDTO;
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
import com.musicrec.service.MultimodalEmotionFusionService;
import com.musicrec.service.MusicRecommendationService;
import com.musicrec.service.SpeechToTextService;
import jakarta.validation.Valid;
import com.musicrec.exception.InvalidAudioException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final AudioTranscodingService audioTranscodingService;
    private final AcousticFeatureExtractor acousticFeatureExtractor;
    private final AcousticEmotionAnalysisService acousticEmotionAnalysisService;
    private final MultimodalEmotionFusionService multimodalEmotionFusionService;
    private final SpeechEmotionClient speechEmotionClient;

    /** Voice weight when fusing the speech-emotion model with the words (0 = words only, 1 = voice only). */
    @Value("${app.ser.audio-weight:0.3}")
    private double audioWeight;

    /** Confidence given to the text label of a voice message (the text analysis returns no confidence). */
    @Value("${app.ser.voice-text-confidence:0.6}")
    private double voiceTextConfidence;

    @Value("${app.recommendation.top-n-results:10}")
    private int topNResults;

    // ── Text Recommendation ───────────────────────────────────────

    /**
     * Accepts free-form text (Hindi or English) and returns top-10 Hindi-language songs.
     * The Hindi-only restriction applies here too — see MusicRecommendationService, which
     * now defaults every recommend() overload to restrictToHindi=true system-wide.
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

        // 2. Retrieve → Fuse → Rerank (Hindi-only)
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
                .warnings(insufficientResultsWarning(result))
                .sessionId(sessionId)
                .timestamp(System.currentTimeMillis())
                .build();

        return ResponseEntity.ok(response);
    }

    // ── Voice Recommendation ──────────────────────────────────────

    /**
     * Accepts an audio file, transcribes it via Groq Whisper, then runs the
     * same emotion + recommendation pipeline as the text endpoint (Hindi-only).
     *
     * Form params:
     *   audio            — the audio blob (WebM / WAV / MP3 / M4A)
     *   sessionId        — optional
     *   userId           — optional
     */
    @PostMapping(value = "/recommend/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RecommendationResponse> recommendByVoice(
            @RequestPart("audio") MultipartFile audioFile,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "emotionSource", required = false) String emotionSource) {

        double weight = SpeechEmotionFusion.audioWeightFor(emotionSource, audioWeight);
        log.info("Voice recommendation request: session={}, audioSize={}",
                sessionId, audioFile.getSize());

        if (audioFile.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        sessionId = resolveSessionId(sessionId);

        // 0. Decode first: rejects non-audio and silent uploads before speech-to-text,
        //    which would otherwise fail with a 500 or hallucinate a transcript from silence.
        short[] pcm = decodeAndValidate(audioFile);
        List<AcousticFeatureExtractor.Frame> frames = pcm == null ? null : acousticFeatureExtractor.analyze(pcm);

        // 1. Speech-to-text
        String transcript = speechToTextService.transcribe(audioFile);

        // 2. Emotion analysis: text-based (on the transcript) + audio-based (acoustic
        //    arousal from the actual voice signal — req. #2), fused into one profile.
        EmotionMetadata textEmotion = emotionAnalysisService.analyze(transcript);
        AudioEmotionProfileDTO audioProfile = frames == null ? null
                : acousticEmotionAnalysisService.analyze(acousticFeatureExtractor.summarize(frames));
        EmotionMetadata emotion = multimodalEmotionFusionService.fuse(textEmotion, audioProfile);

        // 2b. Emotion from the voice itself (speech-emotion model), fused with the words.
        List<String> warnings = new java.util.ArrayList<>();
        java.util.Optional<SpeechEmotionClient.Result> voice = pcm == null ? java.util.Optional.empty()
                : speechEmotionClient.analyze(pcm, List.of(), false);
        if (voice.isPresent() && voice.get().overall() != null) {
            emotion = multimodalEmotionFusionService.fuseSpeechEmotion(emotion, voice.get().overall(), voiceTextConfidence, weight);
        } else if (pcm != null) {
            warnings.add("The voice emotion model (speech-emotion service) was unavailable — the emotion comes from "
                    + "the words and vocal arousal only.");
        }

        // 3. Retrieve → Fuse → Rerank (Hindi-only)
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
                .warnings(concat(warnings, insufficientResultsWarning(result)))
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

    // ── Audio-based emotion helper ──────────────────────────────────

    /** Decodes the voice recording and computes its acoustic arousal profile; degrades to null on failure. */
    /**
     * Decodes the upload to acoustic frames, rejecting it (400) if it is not decodable audio or
     * contains no voiced speech. Returns null only when ffmpeg itself is unavailable, in which
     * case the request continues with text-only analysis, as before.
     */
    private short[] decodeAndValidate(MultipartFile audioFile) {
        List<AcousticFeatureExtractor.Frame> frames;
        short[] pcm;
        try {
            pcm = audioTranscodingService.toPcm16Mono16k(audioFile.getBytes(), audioFile.getOriginalFilename());
            frames = acousticFeatureExtractor.analyze(pcm);
        } catch (AudioTranscodingService.UndecodableAudioException ex) {
            log.warn("Rejected voice upload that could not be decoded: {}", ex.getMessage());
            throw new InvalidAudioException("The uploaded file could not be read as audio. Please upload a WAV, MP3, M4A or WebM recording.");
        } catch (Exception ex) {
            log.warn("Acoustic emotion analysis unavailable for this voice recording: {}", ex.getMessage());
            return null;
        }
        if (!AcousticFeatureExtractor.containsSpeech(frames)) {
            throw new InvalidAudioException("No speech was detected in the recording. Please check the microphone and try again.");
        }
        return pcm;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    // ── Hindi-only warning helper ────────────────────────────────────

    private List<String> insufficientResultsWarning(MusicRecommendationService.RankingResult result) {
        if (result.tracks().size() >= topNResults) return List.of();
        return List.of("Only " + result.tracks().size() + " verified Hindi-language track(s) matched this "
                + "emotional profile — the catalogue needs more eligible Hindi tracks to reach " + topNResults
                + ". Showing all verified matches found; no non-Hindi track was substituted.");
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
