package com.musicrec.dto;

/**
 * One labeled turn in a two-speaker conversation transcript.
 *
 * Speaker roles come from text-based turn segmentation (see
 * ConversationAnalysisService), not audio speaker diarization — the model
 * reasons over conversational cues (who asks vs. who describes symptoms/
 * feelings) rather than voice characteristics. This is documented as a
 * known limitation: true audio diarization would need a dedicated model
 * (e.g. pyannote.audio) that isn't part of this Spring Boot stack.
 */
public record ConversationTurnDTO(int order, String speakerRole, String text) {

    public static final String HEALTH_CONSULTANT = "HEALTH_CONSULTANT";
    public static final String PATIENT = "PATIENT";
}
