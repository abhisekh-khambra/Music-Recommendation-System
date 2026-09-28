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

    /** Top-N recommended tracks (with individual explanations). */
    private List<TrackDTO> recommendations;

    private String conversationalMessage;

    /** Non-fatal degradations the caller should know about (e.g. speaker segmentation fallback used). */
    private List<String> warnings;

    private String sessionId;
    private long timestamp;
}
