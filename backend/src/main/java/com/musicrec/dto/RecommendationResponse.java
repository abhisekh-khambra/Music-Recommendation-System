package com.musicrec.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Full API response returned to the React frontend */
@Data
@Builder
public class RecommendationResponse {

    /** Echo of the processed user message */
    private String userMessage;

    /** Transcript from STT — same as userMessage for text input */
    private String transcript;

    /** Detected emotion profile */
    private EmotionMetadata emotion;

    /** Top-10 recommended tracks (with individual explanations) */
    private List<TrackDTO> recommendations;

    /** Conversational message from the LLM explaining the playlist */
    private String conversationalMessage;

    /** Session id for chat continuity */
    private String sessionId;

    /** Timestamp (epoch ms) */
    private long timestamp;
}
