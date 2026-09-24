package com.musicrec.dto;

/**
 * One atomic, code-verified fact about why a track fits the user's emotional
 * profile — the "evidence" in the propose-assign-select pipeline.
 *
 * Produced deterministically from real track/emotion fields (never by an
 * LLM), so it can be handed to the reranker as ground truth the model is
 * constrained to cite rather than invent.
 */
public record EvidenceRecord(Long trackId, String type, String text, double strength) {
}
