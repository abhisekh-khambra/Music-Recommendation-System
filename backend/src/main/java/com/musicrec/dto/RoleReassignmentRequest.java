package com.musicrec.dto;

import lombok.Data;

import java.util.List;

/**
 * Body for POST /api/v1/conversation/reassign-roles — the user-correction path for
 * req. #3: "Allow the user to correct speaker-role assignments when automatic role
 * identification is uncertain." The frontend resends the SAME turns it was given
 * (text/timestamps unchanged) with the user's corrected speakerRole per turn; no
 * audio re-upload is needed since diarization/transcription don't need to rerun —
 * only the downstream patient-text/emotion/raga/recommendation stages do.
 */
@Data
public class RoleReassignmentRequest {

    private String sessionId;

    private List<TurnInput> turns;

    @Data
    public static class TurnInput {
        private int order;
        private String speakerRole;
        private String text;
        private long startMs;
        private long endMs;
    }
}
