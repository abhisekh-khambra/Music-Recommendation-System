package com.musicrec.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Full API response for POST /api/v1/conversation/analyze */
@Data
@Builder
public class ConversationRecommendationResponse {

    /** Raw Speech-to-Text output, unlabeled. */
    private String fullTranscript;

    /** Transcript segmented into speaker turns (Health Consultant / Patient). */
    private List<ConversationTurnDTO> turns;

    /** Concatenated Patient-only utterances — what conversation analysis actually ran on. */
    private String patientText;

    private ConversationAnalysisDTO analysis;
    private RagaSelectionDTO ragaSelection;

    /** Audio-based (acoustic) arousal profile over the Patient's speech only — see AudioEmotionProfileDTO. */
    private AudioEmotionProfileDTO audioEmotionProfile;

    /** Per-segment Patient emotion predictions + aggregated variability stats (req. #3). */
    private EmotionalVariabilityDTO emotionalVariability;

    /** How each patient response's emotion was derived from the voice (speech-emotion model + fusion weight), or why not. */
    private String speechEmotionMethod;

    /** Human-readable description of how speakers were separated (audio diarization vs. text fallback). */
    private String diarizationMethod;

    /**
     * True when automatic speaker-role identification wasn't reliable enough to trust — the
     * frontend should prompt the user to review/correct turns.speakerRole and resubmit via
     * POST /api/v1/conversation/reassign-roles rather than silently trusting a possibly-wrong split.
     */
    private boolean speakerAssignmentUncertain;

    /** Top-N recommended tracks (with individual explanations). All guaranteed language=Hindi. */
    private List<TrackDTO> recommendations;

    /**
     * How the Top-N was split across the Patient's emotions — each emotion's share of the responses,
     * the slots that share earned, and how many were filled with a matching song (EmotionQuotaAllocator).
     */
    private List<EmotionSlotDTO> emotionAllocation;

    /**
     * False when the LLM song-selection step was unavailable (e.g. Groq rate limit) and the songs are
     * in fused-rank order with evidence-based explanations instead.
     */
    private boolean llmSongSelection;

    private String conversationalMessage;

    /** Non-fatal degradations the caller should know about (e.g. speaker segmentation fallback used). */
    private List<String> warnings;

    private String sessionId;
    private long timestamp;
}
