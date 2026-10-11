package com.musicrec.service;

import com.musicrec.audio.AcousticEmotionAnalysisService;
import com.musicrec.audio.AcousticFeatureExtractor;
import com.musicrec.audio.SpeechEmotionClient;
import com.musicrec.audio.SpeechEmotionFusion;
import com.musicrec.dto.AudioEmotionProfileDTO;
import com.musicrec.dto.ConversationRecommendationResponse;
import com.musicrec.dto.ConversationTurnDTO;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.EmotionalVariabilityDTO;
import com.musicrec.dto.PatientSegmentEmotionDTO;
import com.musicrec.dto.RagaSelectionDTO;
import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.ChatHistory;
import com.musicrec.repository.ChatHistoryRepository;
import com.musicrec.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The role-dependent half of the conversation pipeline, from speaker-labelled turns onward:
 *
 *   Patient turns → Patient-only Segment Emotion Analysis (text + acoustic arousal, per segment)
 *   → Non-diagnostic Analysis + Emotional-Variability Profile
 *   → Raga Selection (overall + one per emotion share) → Hindi-only Raga-Based Candidate Songs
 *   → Top-N split across the Patient's emotions by share, each share preferring its raga
 *
 * Shared by ConversationController (audio upload and role correction) and EvaluationService
 * (labelled transcripts), so evaluation measures exactly the pipeline users get.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationPipelineService {

    private final ConversationAnalysisService conversationAnalysisService;
    private final EmotionAnalysisService emotionAnalysisService;
    private final RagaSelectionService ragaSelectionService;
    private final MusicRecommendationService recommendationService;
    private final AcousticFeatureExtractor acousticFeatureExtractor;
    private final AcousticEmotionAnalysisService acousticEmotionAnalysisService;
    private final ChatHistoryRepository chatHistoryRepository;
    private final UserRepository userRepository;

    @Value("${app.recommendation.top-n-results:10}")
    private int topNResults;

    /**
     * Voice emotion for this conversation's speech segments (from the speech-emotion model) and how much
     * weight the voice gets against the words when they are fused (0 = words only, 1 = voice only).
     */
    public record VoiceEvidence(List<SpeechEmotionClient.SegmentAnalysis> segments, double audioWeight, String model) {}

    /** Without voice evidence (no audio, or the speech-emotion service unavailable). */
    public ConversationRecommendationResponse run(
            String fullTranscript, List<ConversationTurnDTO> turns, List<AcousticFeatureExtractor.Frame> frames,
            String diarizationMethod, boolean speakerAssignmentUncertain, List<String> warnings,
            String sessionId, Long userId, boolean persist) {
        return run(fullTranscript, turns, frames, diarizationMethod, speakerAssignmentUncertain, warnings,
                sessionId, userId, persist, null);
    }

    /**
     * @param frames  decoded audio frames for acoustic analysis; empty when there is no audio
     *                (role correction, text transcripts), in which case acoustic fields report UNKNOWN
     * @param persist false for evaluation runs, so they don't fill the user's chat history
     */
    public ConversationRecommendationResponse run(
            String fullTranscript,
            List<ConversationTurnDTO> turns,
            List<AcousticFeatureExtractor.Frame> frames,
            String diarizationMethod,
            boolean speakerAssignmentUncertain,
            List<String> warnings,
            String sessionId,
            Long userId,
            boolean persist,
            VoiceEvidence voice) {

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

        List<PatientSegmentEmotionDTO> segmentEmotions = buildSegmentEmotions(patientTurns, frames, voice);
        EmotionalVariabilityDTO variability = conversationAnalysisService.buildVariability(segmentEmotions);

        // Raga selection — weighted by the distribution of emotions across the Patient's responses
        // (not just the single overall label), and Hindi-aware (only counts Hindi-tagged tracks).
        RagaSelectionDTO ragaSelection = ragaSelectionService.selectRaga(
                patientEmotion, variability.getEmotionDistribution(), true);
        warnings.addAll(ragaSelection.getWarnings());

        // Hindi-only Raga-based retrieve → fuse → evidence → rerank → select, then the Top-N is split
        // across the Patient's emotions by their share of the responses (50% SADNESS → 5 of 10 songs).
        // One raga per emotion share ("multiple emotions, multiple raagas") — each share is filled
        // preferring songs in its own raga.
        RagaSelectionService.RagaPerEmotion shareRagas =
                ragaSelectionService.ragasPerEmotion(variability.getEmotionDistribution(), true);
        warnings.addAll(shareRagas.warnings());

        EmotionMetadata retrievalEmotion = conversationAnalysisService.withDistributionEmotions(patientEmotion, variability);
        MusicRecommendationService.RankingResult result = recommendationService.recommend(
                retrievalEmotion, ragaSelection.getRaga(), true,
                new MusicRecommendationService.PlaylistPlan(variability.getEmotionDistribution(), shareRagas.ragaByEmotion()));
        if (result.tracks().size() < topNResults) {
            warnings.add("Only " + result.tracks().size() + " verified Hindi-language track(s) matched this "
                    + "emotional/Raga profile — the catalogue needs more eligible Hindi tracks to reach "
                    + topNResults + ". Showing all verified matches found; no non-Hindi track was substituted.");
        }
        if (!result.llmSelectionUsed()) {
            warnings.add("The AI song selection step was unavailable (e.g. rate limit), so song selection fell back "
                    + "to the fused retrieval ranking — songs and explanations come from catalogue evidence only.");
        }
        result.emotionAllocation().stream()
                .filter(s -> s.filledSlots() < s.targetSlots())
                .forEach(s -> warnings.add(s.emotion() + " accounts for " + Math.round(s.sharePercent())
                        + "% of the patient's responses (" + s.targetSlots() + " of " + topNResults
                        + " songs), but only " + s.filledSlots() + " eligible Hindi " + s.emotion()
                        + " song(s) were found — the remaining slot(s) were filled with the next-best matches."));

        if (persist) {
            persistHistory(fullTranscript, turns, patientEmotion, ragaSelection, result, sessionId, userId);
        }

        return ConversationRecommendationResponse.builder()
                .fullTranscript(fullTranscript)
                .turns(turns)
                .patientText(patientText)
                .analysis(conversationAnalysisService.buildAnalysis(patientEmotion))
                .ragaSelection(ragaSelection)
                .audioEmotionProfile(audioProfile)
                .emotionalVariability(variability)
                .speechEmotionMethod(voice == null
                        ? "Voice emotion not available — each response's emotion comes from the words only."
                        : String.format("Each response's emotion fuses the words with the voice (%s); voice weight %.2f "
                          + "(0 = words only, 1 = voice only).", voice.model(), voice.audioWeight()))
                .diarizationMethod(diarizationMethod)
                .speakerAssignmentUncertain(speakerAssignmentUncertain)
                .recommendations(result.tracks())
                .emotionAllocation(result.emotionAllocation())
                .llmSongSelection(result.llmSelectionUsed())
                .conversationalMessage(result.conversationalMessage())
                .warnings(warnings)
                .sessionId(sessionId)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // ── Acoustic helpers ──────────────────────────────────────────

    private List<AcousticFeatureExtractor.Frame> framesWithinTurns(
            List<AcousticFeatureExtractor.Frame> frames, List<ConversationTurnDTO> turns) {
        if (frames.isEmpty() || turns.isEmpty()) return List.of();
        return frames.stream()
                .filter(f -> turns.stream().anyMatch(t -> f.timeMs() >= t.startMs() && f.timeMs() < t.endMs()))
                .toList();
    }

    private List<PatientSegmentEmotionDTO> buildSegmentEmotions(
            List<ConversationTurnDTO> patientTurns, List<AcousticFeatureExtractor.Frame> frames, VoiceEvidence voice) {
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

            String textLabel = textEmotion != null ? textEmotion.primaryEmotion() : "RELAXATION";
            double textConfidence = textEmotion != null ? textEmotion.confidence() : 0.2;
            SpeechEmotionClient.SpeechEmotion voiceEmotion = voiceFor(turn, voice);
            SpeechEmotionFusion.Fused fused = SpeechEmotionFusion.fuse(textLabel, textConfidence, voiceEmotion,
                    voice == null ? 0 : voice.audioWeight());

            result.add(PatientSegmentEmotionDTO.builder()
                    .order(turn.order())
                    .startMs(turn.startMs())
                    .endMs(turn.endMs())
                    .text(turn.text())
                    .primaryEmotion(fused.label())
                    .textEmotion(textLabel)
                    .voiceEmotion(voiceEmotion == null ? null : voiceEmotion.label())
                    .voiceEmotionConfidence(voiceEmotion == null ? null : voiceEmotion.confidence())
                    .emotionSource(fused.source())
                    .fusionNote(fused.note())
                    .intensity(textEmotion != null ? textEmotion.intensity() : 0.5)
                    .confidence(textConfidence)
                    .audioArousalLevel(segmentAudio != null ? segmentAudio.getArousalLevel() : null)
                    .audioConfidence(segmentAudio != null ? segmentAudio.getConfidence() : null)
                    .build());
        }
        return result;
    }

    /** The voice prediction for a turn: the analysed segment with the same time range, else the most overlapping one. */
    private SpeechEmotionClient.SpeechEmotion voiceFor(ConversationTurnDTO turn, VoiceEvidence voice) {
        if (voice == null || voice.segments() == null) return null;
        SpeechEmotionClient.SegmentAnalysis best = null;
        long bestOverlap = 0;
        for (SpeechEmotionClient.SegmentAnalysis s : voice.segments()) {
            if (s.emotion() == null) continue;
            long overlap = Math.min(s.endMs(), turn.endMs()) - Math.max(s.startMs(), turn.startMs());
            if (overlap > bestOverlap) { bestOverlap = overlap; best = s; }
        }
        return best == null ? null : best.emotion();
    }

    // ── History ───────────────────────────────────────────────────

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
