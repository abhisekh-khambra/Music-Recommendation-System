package com.musicrec.dto;

/**
 * One time-aligned segment as returned directly by the Whisper
 * speech-to-text model's verbose_json output (see SpeechToTextService) —
 * real decoded timestamps from the audio, not an LLM-inferred split.
 */
public record TranscriptSegmentDTO(int order, long startMs, long endMs, String text) {
}
