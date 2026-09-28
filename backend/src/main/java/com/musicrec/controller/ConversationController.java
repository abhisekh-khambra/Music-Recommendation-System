package com.musicrec.controller;

import com.musicrec.dto.ConversationRecommendationResponse;
import com.musicrec.dto.ConversationTurnDTO;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.RagaSelectionDTO;
import com.musicrec.entity.ChatHistory;
import com.musicrec.exception.ConversationProcessingException;
import com.musicrec.exception.InvalidAudioException;
import com.musicrec.dto.TrackDTO;
import com.musicrec.repository.ChatHistoryRepository;
import com.musicrec.repository.UserRepository;
import com.musicrec.service.ConversationAnalysisService;
import com.musicrec.service.EmotionAnalysisService;
import com.musicrec.service.MusicRecommendationService;
import com.musicrec.service.RagaSelectionService;
import com.musicrec.service.SpeechToTextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Conversation-based, Raga-centric recommendation pipeline:
 *
 * Conversation Audio → Speech-to-Text → Speaker Turn Segmentation
 *   → Patient Conversation Analysis → Sentiment/Mood/Emotional-State
 *   → Raga Selection → Raga-Based Candidate Songs → Ranked Recommendations
 *
 * This is additive: it does not touch /recommend/text or /recommend/voice,
 * which keep working exactly as before.
 */
@RestController
@RequestMapping("/api/v1/conversation")
@RequiredArgsConstructor
@Slf4j
public class ConversationController {

    private static final long MAX_AUDIO_BYTES = 25L * 1024 * 1024; // 25MB — Groq Whisper's own limit
    private static final Set<String> ACCEPTED_CONTENT_PREFIXES = Set.of("audio/", "video/webm");

    private final SpeechToTextService speechToTextService;
    private final ConversationAnalysisService conversationAnalysisService;
    private final EmotionAnalysisService emotionAnalysisService;
    private final RagaSelectionService ragaSelectionService;
    private final MusicRecommendationService recommendationService;
    private final ChatHistoryRepository chatHistoryRepository;
    private final UserRepository userRepository;

    /**
     * Accepts a recorded conversation audio file (Health Consultant + Patient) and runs
     * the full pipeline through to a Raga-based, explainable top-10 recommendation.
     *
     * Form params:
     *   audio     — the conversation audio file (WebM / WAV / MP3 / M4A)
     *   sessionId — optional
     *   userId    — optional
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ConversationRecommendationResponse> analyzeConversation(
            @RequestPart("audio") MultipartFile audioFile,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "userId", required = false) Long userId) {

        validateAudio(audioFile);
        sessionId = resolveSessionId(sessionId);

        log.info("Conversation analysis request: session={}, audioSize={}", sessionId, audioFile.getSize());

        // 1. Speech-to-Text
        String transcript = speechToTextService.transcribe(audioFile);
        if (transcript == null || transcript.isBlank()) {
            throw new ConversationProcessingException(
                    "Could not detect any meaningful speech in the uploaded audio. "
                    + "Please check the recording and try again.");
        }

        // 2. Speaker turn segmentation (Health Consultant vs. Patient — see ConversationAnalysisService)
        ConversationAnalysisService.SegmentationResult segmentation = conversationAnalysisService.segment(transcript);
        List<String> warnings = new ArrayList<>(segmentation.warnings());

        // 3. Conversation analysis — emotion metadata over the Patient's turns only,
        //    then a non-diagnostic, code-built analysis panel from it.
        EmotionMetadata patientEmotion = emotionAnalysisService.analyze(segmentation.patientText());

        // 4. Raga selection — the central step (pure code, explainable — see RagaSelectionService)
        RagaSelectionDTO ragaSelection = ragaSelectionService.selectRaga(patientEmotion);
        warnings.addAll(ragaSelection.getWarnings());

        // 5. Raga-based candidate songs → emotion + raga + metadata ranking → top-10
        MusicRecommendationService.RankingResult result =
                recommendationService.recommend(patientEmotion, ragaSelection.getRaga());

        // 6. Persist + respond
        persistHistory(transcript, segmentation, patientEmotion, ragaSelection, result, sessionId, userId);

        ConversationRecommendationResponse response = ConversationRecommendationResponse.builder()
                .fullTranscript(transcript)
                .turns(segmentation.turns())
                .patientText(segmentation.patientText())
                .analysis(conversationAnalysisService.buildAnalysis(patientEmotion))
                .ragaSelection(ragaSelection)
                .recommendations(result.tracks())
                .conversationalMessage(result.conversationalMessage())
                .warnings(warnings)
                .sessionId(sessionId)
                .timestamp(System.currentTimeMillis())
                .build();

        return ResponseEntity.ok(response);
    }

    // ── Validation ────────────────────────────────────────────────

    private void validateAudio(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new InvalidAudioException("No audio file was received — please upload a conversation recording.");
        }
        if (audioFile.getSize() > MAX_AUDIO_BYTES) {
            throw new InvalidAudioException("Audio file is too large (max 25MB).");
        }
        String contentType = audioFile.getContentType();
        if (contentType != null && ACCEPTED_CONTENT_PREFIXES.stream().noneMatch(contentType::startsWith)) {
            log.warn("Unexpected content type for conversation audio: {}", contentType);
            // Not fatal — browsers/recorders report inconsistent content types — but logged for visibility.
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private String resolveSessionId(String provided) {
        return (provided != null && !provided.isBlank()) ? provided : UUID.randomUUID().toString();
    }

    private void persistHistory(String transcript,
                                 ConversationAnalysisService.SegmentationResult segmentation,
                                 EmotionMetadata emotion,
                                 RagaSelectionDTO ragaSelection,
                                 MusicRecommendationService.RankingResult result,
                                 String sessionId,
                                 Long userId) {
        try {
            ChatHistory history = new ChatHistory();
            history.setSessionId(sessionId);
            history.setUserMessage(transcript);
            history.setDetectedEmotion(emotion.getPrimaryEmotionSafe());
            history.setEmotionIntensity(BigDecimal.valueOf(emotion.getIntensity()));
            history.setAssistantResponse(result.conversationalMessage());
            history.setRecommendedTrackIds(
                    result.tracks().stream().map(TrackDTO::getId).collect(Collectors.toList()));
            history.setInputType(ChatHistory.InputType.conversation);
            history.setLanguage(emotion.getLanguageSafe());
            history.setSelectedRaga(ragaSelection.getRaga());
            history.setSpeakerTurns(segmentation.turns().stream()
                    .map(t -> labelFor(t) + ": " + t.text())
                    .collect(Collectors.toList()));

            if (userId != null) {
                userRepository.findById(userId).ifPresent(history::setUser);
            } else {
                userRepository.findByUsername("anonymous").ifPresent(history::setUser);
            }

            chatHistoryRepository.save(history);
        } catch (Exception ex) {
            log.warn("Failed to persist conversation history: {}", ex.getMessage());
        }
    }

    private String labelFor(ConversationTurnDTO turn) {
        return ConversationTurnDTO.HEALTH_CONSULTANT.equals(turn.speakerRole()) ? "Health Consultant" : "Patient";
    }
}
