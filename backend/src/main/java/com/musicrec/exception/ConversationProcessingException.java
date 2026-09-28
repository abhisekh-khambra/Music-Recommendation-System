package com.musicrec.exception;

/**
 * Thrown when audio was valid but the pipeline couldn't produce a usable
 * result from it (e.g. transcript came back empty/unintelligible). Maps to
 * HTTP 422 Unprocessable Entity — distinct from a malformed request (400) or
 * an unexpected server fault (500).
 */
public class ConversationProcessingException extends RuntimeException {
    public ConversationProcessingException(String message) {
        super(message);
    }
}
