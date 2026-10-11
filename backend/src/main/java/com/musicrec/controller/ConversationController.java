package com.musicrec.controller;

import com.musicrec.audio.AcousticEmotionAnalysisService;
import com.musicrec.audio.AcousticFeatureExtractor;
import com.musicrec.audio.AudioTranscodingService;
import com.musicrec.audio.SpeakerDiarizationService;
import com.musicrec.dto.AudioEmotionProfileDTO;
import com.musicrec.dto.ConversationRecommendationResponse;
import com.musicrec.dto.ConversationTurnDTO;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.EmotionalVariabilityDTO;
import com.musicrec.dto.PatientSegmentEmotionDTO;
import com.musicrec.dto.RagaSelectionDTO;
import com.musicrec.dto.RoleReassignmentRequest;
import com.musicrec.entity.ChatHistory;
import com.musicrec.exception.ConversationProcessingException;
import com.musicrec.exception.InvalidAudioException;
import com.musicrec.dto.TrackDTO;
import com.musicrec.dto.TranscriptSegmentDTO;
import com.musicrec.repository.ChatHistoryRepository;
import com.musicrec.repository.UserRepository;
import com.musicrec.service.ConversationAnalysisService;
import com.musicrec.service.EmotionAnalysisService;
import com.musicrec.service.MusicRecommendationService;
import com.musicrec.service.RagaSelectionService;
import com.musicrec.service.SpeakerRoleResolver;
import com.musicrec.service.SpeechToTextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * Conversation Audio → Segmented Speech-to-Text (real timestamps)
 *   → Audio-based Speaker Diarization (acoustic clustering) + Text-based Role Labeling
 *   → [optional user correction via /reassign-roles]
 *   → Patient-only Segment Emotion Analysis (text + acoustic arousal, per segment)
 *   → Non-diagnostic Analysis + Emotional-Variability Profile
 *   → Raga Selection → Hindi-only Raga-Based Candidate Songs → Ranked Recommendations
 *
 * This is additive: it does not remove /recommend/text or /recommend/voice. The
 * Hindi-only restriction (req. #2) is now enforced system-wide (see
 * MusicRecommendationService), and real audio-based speaker diarization +
 * acoustic emotion analysis apply here specifically.
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
    private final SpeakerRoleResolver speakerRoleResolver;
    private final EmotionAnalysisService emotionAnalysisService;
    private final RagaSelectionService ragaSelectionService;
    private final MusicRecommendationService recommendationService;
    private final AudioTranscodingService audioTranscodingService;
    private final AcousticFeatureExtractor acousticFeatureExtractor;
    private final AcousticEmotionAnalysisService acousticEmotionAnalysisService;
    private final SpeakerDiarizationService speakerDiarizationService;
    private final ChatHistoryRepository chatHistoryRepository;
    private final UserRepository userRepository;

    @Value("${app.recommendation.top-n-results:10}")
    private int topNResults;

    /**
     * Accepts a recorded conversation audio file (Health Consultant + Patient) and runs
     * the full pipeline through to a Raga-based, explainable, Hindi-only top-10 recommendation.
     *
     * Form params:
     *   audio            — the conversation audio file (WebM / WAV / MP3 / M4A)
     *   sessionId        — optional
     *   userId           — optional
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ConversationRecommendationResponse> analyzeConversation(
            @RequestPart("audio") MultipartFile audioFile,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "userId", required = false) Long userId) {

        validateAudio(audioFile);
        sessionId = resolveSessionId(sessionId);
        List<String> warnings = new ArrayList<>();

        log.info("Conversation analysis request: session={}, audioSize={}", sessionId, audioFile.getSize());

        // 0. Decode first: rejects non-audio and silent uploads before speech-to-text,
        //    which would otherwise fail with a 500 or hallucinate a transcript from silence.
        List<AcousticFeatureExtractor.Frame> frames = decodeFramesOrEmpty(audioFile, warnings);

        // 1. Segmented Speech-to-Text — real per-segment timestamps decoded from the audio.
        SpeechToTextService.SegmentedTranscript transcript = speechToTextService.transcribeWithSegments(audioFile);
        if (transcript.fullText() == null || transcript.fullText().isBlank()) {
            throw new ConversationProcessingException(
                    "Could not detect any meaningful speech in the uploaded audio. "
                    + "Please check the recording and try again.");
        }

        // 1b. Drop segments with no detectable speech in the audio — speech-to-text hallucinates text
        //     over silence (e.g. "you" over a recording's silent tail), and such a segment would
        //     otherwise become a fake turn and skew speaker clustering.
        List<TranscriptSegmentDTO> speechSegments = dropSegmentsWithoutSpeech(transcript.segments(), frames, warnings);
        String fullText = speechSegments.stream().map(TranscriptSegmentDTO::text).collect(Collectors.joining(" "));

        // 2. Audio-based speaker diarization (acoustic clustering by pitch/energy — real
        //    signal, not LLM text segmentation) + text-based role labeling, then resolved.
        SpeakerDiarizationService.DiarizationResult diarization =
                speakerDiarizationService.diarize(frames, speechSegments);
        List<String> textRoles = conversationAnalysisService.labelRoles(speechSegments);
        SpeakerRoleResolver.Resolution resolution = speakerRoleResolver.resolve(diarization, textRoles);
        warnings.addAll(resolution.warnings());

        // Surfaced to the user via the dedicated speakerAssignmentUncertain banner (with its own
        // "Review & Correct" action) rather than a duplicate warnings-array message — resolution's
        // own warning above already explains *why* in diagnostic terms.
        boolean speakerAssignmentUncertain = !diarization.reliable();

        ConversationRecommendationResponse response = buildResponseFromTurns(
                fullText, resolution.turns(), frames, resolution.diarizationMethod(),
                speakerAssignmentUncertain, warnings, sessionId, userId);

        return ResponseEntity.ok(response);
    }

    /**
     * Req. #3 — user-driven correction of speaker roles when automatic identification was
     * uncertain. Takes the SAME turns the client was given (with the user's corrected
     * speakerRole values) and recomputes everything downstream (patient text, emotion,
     * variability, Raga selection, Hindi-only recommendations) without needing the audio
     * again — transcription/diarization don't need to rerun, only the role-dependent stages.
     */
    @PostMapping("/reassign-roles")
    public ResponseEntity<ConversationRecommendationResponse> reassignRoles(
            @RequestBody RoleReassignmentRequest request) {

        if (request.getTurns() == null || request.getTurns().isEmpty()) {
            throw new InvalidAudioException("No turns were provided to reassign roles for.");
        }

        List<ConversationTurnDTO> turns = request.getTurns().stream()
                .map(t -> new ConversationTurnDTO(
                        t.getOrder(), normalizeRole(t.getSpeakerRole()), t.getText(), t.getStartMs(), t.getEndMs()))
                .sorted((a, b) -> Integer.compare(a.order(), b.order()))
                .toList();

        String fullTranscript = turns.stream().map(ConversationTurnDTO::text).collect(Collectors.joining(" "));
        String sessionId = resolveSessionId(request.getSessionId());
        List<String> warnings = new ArrayList<>();
        warnings.add("Speaker roles were manually corrected by the user — audio was not re-analyzed, "
                + "so the audio-based arousal profile below reflects the ORIGINAL automatic split, not this correction.");

        // No audio to re-decode here — frames stay empty, so acoustic analysis honestly
        // reports UNKNOWN rather than fabricating a re-analysis that didn't happen.
        ConversationRecommendationResponse response = buildResponseFromTurns(
                fullTranscript, turns, List.of(),
                "Speaker roles manually corrected by the user via /reassign-roles.",
                false, warnings, sessionId, null);

        return ResponseEntity.ok(response);
    }

    // ── Shared pipeline (from turns onward) ──────────────────────────

    private ConversationRecommendationResponse buildResponseFromTurns(
            String fullTranscript,
            List<ConversationTurnDTO> turns,
            List<AcousticFeatureExtractor.Frame> frames,
            String diarizationMethod,
            boolean speakerAssignmentUncertain,
            List<String> warnings,
            String sessionId,
            Long userId) {

        List<ConversationTurnDTO> patientTurns = turns.stream()
                .filter(t -> ConversationTurnDTO.PATIENT.equals(t.speakerRole()))
                .toList();
        if (patientTurns.isEmpty()) {
            warnings.add("Could not isolate any Patient turns from the transcript — the full transcript was used for conversation analysis instead.");
            patientTurns = turns;
        }
        String patientText = patientTurns.stream().map(ConversationTurnDTO::text).collect(Collectors.joining(" "));

        // Patient-focused emotion analysis — text (whole patient text) + audio (acoustic
        // arousal over the same patient-labeled time ranges) + per-segment variability.
        EmotionMetadata patientEmotion = emotionAnalysisService.analyze(patientText);

        AcousticFeatureExtractor.Summary patientAcoustics = acousticFeatureExtractor.summarize(
                framesWithinTurns(frames, patientTurns));
        AudioEmotionProfileDTO audioProfile = acousticEmotionAnalysisService.analyze(patientAcoustics);

        List<PatientSegmentEmotionDTO> segmentEmotions = buildSegmentEmotions(patientTurns, frames);
        EmotionalVariabilityDTO variability = conversationAnalysisService.buildVariability(segmentEmotions);

        // Raga selection — weighted by the distribution of emotions across the Patient's responses
        // (not just the single overall label), and Hindi-aware (only counts Hindi-tagged tracks).
        RagaSelectionDTO ragaSelection = ragaSelectionService.selectRaga(
                patientEmotion, variability.getEmotionDistribution(), true);
        warnings.addAll(ragaSelection.getWarnings());

        // Hindi-only Raga-based retrieve → fuse → evidence → rerank → select. Retrieval also uses the
        // distribution's other emotions (most frequent first) so the playlist reflects the whole mix.
        EmotionMetadata retrievalEmotion = conversationAnalysisService.withDistributionEmotions(patientEmotion, variability);
        MusicRecommendationService.RankingResult result =
                recommendationService.recommend(retrievalEmotion, ragaSelection.getRaga(), true);
        if (result.tracks().size() < topNResults) {
            warnings.add("Only " + result.tracks().size() + " verified Hindi-language track(s) matched this "
                    + "emotional/Raga profile — the catalogue needs more eligible Hindi tracks to reach "
                    + topNResults + ". Showing all verified matches found; no non-Hindi track was substituted.");
        }

        persistHistory(fullTranscript, turns, patientEmotion, ragaSelection, result, sessionId, userId);

        return ConversationRecommendationResponse.builder()
                .fullTranscript(fullTranscript)
                .turns(turns)
                .patientText(patientText)
                .analysis(conversationAnalysisService.buildAnalysis(patientEmotion))
                .ragaSelection(ragaSelection)
                .audioEmotionProfile(audioProfile)
                .emotionalVariability(variability)
                .diarizationMethod(diarizationMethod)
                .speakerAssignmentUncertain(speakerAssignmentUncertain)
                .recommendations(result.tracks())
                .conversationalMessage(result.conversationalMessage())
                .warnings(warnings)
                .sessionId(sessionId)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // ── Acoustic helpers ──────────────────────────────────────────

    /** Decodes the upload to PCM for acoustic analysis; degrades to an empty frame list (no acoustic signal) on failure. */
    private List<AcousticFeatureExtractor.Frame> decodeFramesOrEmpty(MultipartFile audioFile, List<String> warnings) {
        List<AcousticFeatureExtractor.Frame> frames;
        try {
            short[] samples = audioTranscodingService.toPcm16Mono16k(audioFile.getBytes(), audioFile.getOriginalFilename());
            frames = acousticFeatureExtractor.analyze(samples);
        } catch (AudioTranscodingService.UndecodableAudioException ex) {
            log.warn("Rejected conversation upload that could not be decoded: {}", ex.getMessage());
            throw new InvalidAudioException("The uploaded file could not be read as audio. Please upload a WAV, MP3, M4A or WebM recording.");
        } catch (Exception ex) {
            log.warn("Audio decoding for acoustic analysis failed — continuing with text-only analysis. Cause: {}", ex.getMessage());
            warnings.add("Audio-based acoustic analysis was unavailable for this recording (the audio decoder, ffmpeg, could not be run) — "
                    + "speaker separation and arousal detection fell back to text-based analysis only.");
            return List.of();
        }
        if (!AcousticFeatureExtractor.containsSpeech(frames)) {
            throw new InvalidAudioException("No speech was detected in the recording. Please check the microphone and try again.");
        }
        return frames;
    }

    /**
     * Removes transcript segments whose time range contains no voiced audio, renumbering the rest.
     * Keeps everything when the audio could not be decoded (no frames), since there is nothing to check against.
     */
    private List<TranscriptSegmentDTO> dropSegmentsWithoutSpeech(
            List<TranscriptSegmentDTO> segments, List<AcousticFeatureExtractor.Frame> frames, List<String> warnings) {
        if (frames.isEmpty()) return segments;
        List<TranscriptSegmentDTO> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (TranscriptSegmentDTO s : segments) {
            if (AcousticFeatureExtractor.containsSpeech(frames, s.startMs(), s.endMs())) {
                kept.add(new TranscriptSegmentDTO(kept.size(), s.startMs(), s.endMs(), s.text()));
            } else {
                dropped.add("\"" + s.text().strip() + "\"");
            }
        }
        if (!dropped.isEmpty()) {
            log.info("Dropped {} transcript segment(s) with no detectable speech: {}", dropped.size(), dropped);
            warnings.add("Ignored " + dropped.size() + " transcript segment(s) with no detectable speech in the audio "
                    + "(likely speech-to-text artifacts over silence): " + String.join(", ", dropped) + ".");
        }
        return kept.isEmpty() ? segments : kept;
    }

    private List<AcousticFeatureExtractor.Frame> framesWithinTurns(
            List<AcousticFeatureExtractor.Frame> frames, List<ConversationTurnDTO> turns) {
        if (frames.isEmpty() || turns.isEmpty()) return List.of();
        return frames.stream()
                .filter(f -> turns.stream().anyMatch(t -> f.timeMs() >= t.startMs() && f.timeMs() < t.endMs()))
                .toList();
    }

    private List<PatientSegmentEmotionDTO> buildSegmentEmotions(
            List<ConversationTurnDTO> patientTurns, List<AcousticFeatureExtractor.Frame> frames) {
        if (patientTurns.isEmpty()) return List.of();

        List<String> texts = patientTurns.stream().map(ConversationTurnDTO::text).toList();
        List<EmotionAnalysisService.SegmentEmotion> textEmotions = emotionAnalysisService.analyzeBatch(texts);

        List<PatientSegmentEmotionDTO> result = new ArrayList<>();
        for (int i = 0; i < patientTurns.size(); i++) {
            ConversationTurnDTO turn = patientTurns.get(i);
            EmotionAnalysisService.SegmentEmotion textEmotion = i < textEmotions.size() ? textEmotions.get(i) : null;

            AudioEmotionProfileDTO segmentAudio = null;
            if (!frames.isEmpty() && turn.endMs() > turn.startMs()) {
                AcousticFeatureExtractor.Summary summary =
                        acousticFeatureExtractor.summarize(frames, turn.startMs(), turn.endMs());
                segmentAudio = acousticEmotionAnalysisService.analyze(summary);
            }

            result.add(PatientSegmentEmotionDTO.builder()
                    .order(turn.order())
                    .startMs(turn.startMs())
                    .endMs(turn.endMs())
                    .text(turn.text())
                    .primaryEmotion(textEmotion != null ? textEmotion.primaryEmotion() : "RELAXATION")
                    .intensity(textEmotion != null ? textEmotion.intensity() : 0.5)
                    .confidence(textEmotion != null ? textEmotion.confidence() : 0.2)
                    .audioArousalLevel(segmentAudio != null ? segmentAudio.getArousalLevel() : null)
                    .audioConfidence(segmentAudio != null ? segmentAudio.getConfidence() : null)
                    .build());
        }
        return result;
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

    private String normalizeRole(String raw) {
        String upper = raw == null ? "" : raw.trim().toUpperCase().replace(' ', '_');
        return ConversationTurnDTO.HEALTH_CONSULTANT.equals(upper) ? ConversationTurnDTO.HEALTH_CONSULTANT
                : ConversationTurnDTO.PATIENT;
    }

    private void persistHistory(String transcript,
                                 List<ConversationTurnDTO> turns,
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
            history.setSpeakerTurns(turns.stream()
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
