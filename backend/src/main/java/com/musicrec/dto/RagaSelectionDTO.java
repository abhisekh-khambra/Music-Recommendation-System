package com.musicrec.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Explainable record of the Raga-selection stage — the central step between
 * detected emotional state and the final song ranking (see RagaSelectionService).
 *
 * Every field is traceable to RagaRasaCatalog (structured, static rasa-theory
 * reference data) plus the track catalogue's actual raga tags — nothing here
 * is an LLM guess, so "reason" can always be justified by "detectedState" +
 * "rasa"/"description".
 */
@Data
@Builder
public class RagaSelectionDTO {

    private String raga;
    private String rasa;
    private String timeOfDay;
    private String description;

    /** The emotional/mental state that drove this selection (e.g. "STRESS"). */
    private String detectedState;
    private double intensity;

    /** Human-readable justification combining detectedState + this raga's rasa/description. */
    private String reason;

    /** Other ragas that were also traditionally associated with the detected state, most-matched first. */
    private List<String> alternateRagas;

    /** How many catalogued tracks exist for the selected raga. */
    private int tracksAvailable;

    /** Populated when the selection had to fall back (e.g. no catalogued tracks for the top raga). */
    private List<String> warnings;
}
