package com.musicrec.evaluation;

import java.util.List;
import java.util.Map;

/** Pure scoring for the audio evaluation (speaker separation and voice / text / fused emotion). */
public final class AudioEvaluationScoring {

    private AudioEvaluationScoring() {}

    /**
     * Share of turns whose speaker cluster matches their true role, under the better of the two possible
     * cluster→role mappings (cluster ids carry no meaning of their own).
     */
    public static double roleAccuracy(int[] clusterIds, List<String> roles) {
        if (roles.isEmpty()) return 0.0;
        String first = roles.get(0);
        int matchA = 0, matchB = 0;
        for (int i = 0; i < roles.size(); i++) {
            boolean isFirstRole = roles.get(i).equals(first);
            if ((clusterIds[i] == 0) == isFirstRole) matchA++; else matchB++;
        }
        return EvaluationScoring.round((double) Math.max(matchA, matchB) / roles.size());
    }

    private static final Map<String, String> VOICE_CLASS = Map.of(
            "SADNESS", "sad", "MELANCHOLY", "sad",
            "JOY", "happy", "EXCITEMENT", "happy",
            "ANGER", "angry", "FRUSTRATION", "angry",
            "CALM", "neutral", "RELAXATION", "neutral");

    /** The voice model's class (neutral/happy/angry/sad) for a gold emotion, or null if it has none (e.g. NOSTALGIA). */
    public static String voiceClass(String goldEmotion) {
        return goldEmotion == null ? null : VOICE_CLASS.get(goldEmotion.toUpperCase());
    }

    /** Share of pairs where prediction equals gold, skipping pairs without a gold label; null if none are labelled. */
    public static Double accuracy(List<String> predicted, List<String> gold) {
        int n = 0, correct = 0;
        for (int i = 0; i < gold.size(); i++) {
            if (gold.get(i) == null) continue;
            n++;
            if (i < predicted.size() && gold.get(i).equalsIgnoreCase(predicted.get(i))) correct++;
        }
        return n == 0 ? null : EvaluationScoring.round((double) correct / n);
    }
}
