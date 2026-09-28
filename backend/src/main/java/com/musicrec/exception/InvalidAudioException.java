package com.musicrec.exception;

/** Thrown for malformed/unusable audio input (empty file, too large, wrong type). Maps to HTTP 400. */
public class InvalidAudioException extends RuntimeException {
    public InvalidAudioException(String message) {
        super(message);
    }
}
