package com.musicrec.dto;

/**
 * One labeled turn in a two-speaker conversation transcript.
 *
 * Speaker roles are resolved by SpeakerRoleResolver from REAL audio-based
 * diarization (acoustic clustering of Whisper's time-aligned segments by
 * pitch/energy — see com.musicrec.audio.SpeakerDiarizationService), with
 * text-based conversational-role labeling (ConversationAnalysisService)
 * used only to decide which acoustic cluster is "Patient" vs. "Health
 * Consultant" — a cluster of voices can't know its own semantic role from
 * acoustics alone. startMs/endMs come directly from Whisper's decoded
 * timestamps; they're 0/0 only in the degraded single-segment fallback path
 * (e.g. when diarization or segment timestamps were unavailable).
 */
public record ConversationTurnDTO(int order, String speakerRole, String text, long startMs, long endMs) {

    public static final String HEALTH_CONSULTANT = "HEALTH_CONSULTANT";
    public static final String PATIENT = "PATIENT";

    /** Convenience factory for turns with no known timestamps (degraded fallback paths). */
    public static ConversationTurnDTO withoutTimestamps(int order, String speakerRole, String text) {
        return new ConversationTurnDTO(order, speakerRole, text, 0L, 0L);
    }
}
