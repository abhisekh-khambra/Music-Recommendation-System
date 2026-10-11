package com.musicrec.evaluation;

import java.util.List;
import java.util.Map;

/**
 * Audio evaluation over labelled recordings (AudioEvaluationService). Speaker separation is scored on the
 * labelled turn boundaries (no speech-to-text), so it measures the separation methods themselves.
 *
 * @param voiceOnlyAccuracy      voice model's class vs the gold emotion collapsed to its 4 classes; responses
 *                               whose gold emotion has no voice class (e.g. NOSTALGIA) are excluded
 * @param textOnlyAccuracy       text model's label vs the gold emotion (12 classes)
 * @param fusedAccuracyByWeight  fused label vs gold (12 classes) per voice weight; "0.0" = text only
 */
public record AudioEvaluationReport(
        String name,
        String source,
        String runAt,
        int recordingsTotal,
        int recordingsCompleted,
        double pitchRoleAccuracy,
        double pitchReliableRate,
        double voiceprintRoleAccuracy,
        double voiceprintReliableRate,
        Double textOnlyAccuracy,
        Double voiceOnlyAccuracy,
        int voiceScoredResponses,
        int patientResponses,
        Map<String, Double> fusedAccuracyByWeight,
        int textFallbackResponses,
        List<RecordingResult> recordings,
        List<String> notes) {

    public record RecordingResult(String id, String error, int turns,
                                  double pitchRoleAccuracy, boolean pitchReliable,
                                  double voiceprintRoleAccuracy, boolean voiceprintReliable,
                                  List<ResponseResult> responses) {}

    public record ResponseResult(String text, String goldEmotion, String textEmotion, double textConfidence,
                                 String voiceEmotion, Double voiceConfidence, Map<String, String> fusedByWeight) {}
}
