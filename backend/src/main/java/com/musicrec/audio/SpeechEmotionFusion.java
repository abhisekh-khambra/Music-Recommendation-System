package com.musicrec.audio;

import com.musicrec.reference.EmotionLabels;

import java.util.*;

/**
 * Explainable late fusion of the text emotion (from the words) with the speech emotion model's
 * prediction (from the voice).
 *
 *   text(e)  = text confidence for the text label, the rest spread evenly over the other emotions
 *   audio(e) = the voice model's probabilities mapped onto the canonical emotions
 *              (sad → SADNESS, happy → JOY, angry → ANGER, neutral → CALM / RELAXATION half each)
 *   fused(e) = (1 − w) · text(e) + w · audio(e)        w = audio weight, 0 = text only, 1 = voice only
 *
 * With w = 0.5 the more confident signal wins: confident words keep their label, while a clear voice
 * decides when the words are ambiguous ("I'm fine" said sadly). The voice model only knows four
 * classes, so finer text labels (NOSTALGIA, STRESS, …) survive unless the voice strongly says otherwise.
 */
public final class SpeechEmotionFusion {

    private SpeechEmotionFusion() {}

    /**
     * @param label      fused emotion
     * @param audioLabel the voice's top canonical emotion, null without audio
     * @param agree      the text label is among the voice's top canonical emotions
     * @param source     "text" or "text+audio"
     */
    public record Fused(String label, String textLabel, String audioLabel, boolean agree,
                        Map<String, Double> scores, String source, String note) {}

    private static final Map<String, Map<String, Double>> VOICE_TO_CANONICAL = Map.of(
            "sad", Map.of("SADNESS", 1.0),
            "happy", Map.of("JOY", 1.0),
            "angry", Map.of("ANGER", 1.0),
            "neutral", Map.of("CALM", 0.5, "RELAXATION", 0.5));

    /** The voice model's probabilities spread onto the canonical emotions (sums to the same total). */
    public static Map<String, Double> toCanonical(SpeechEmotionClient.SpeechEmotion audio) {
        Map<String, Double> out = new LinkedHashMap<>();
        audio.probabilities().forEach((voiceLabel, p) ->
                VOICE_TO_CANONICAL.getOrDefault(voiceLabel, Map.of()).forEach((e, share) -> out.merge(e, p * share, Double::sum)));
        return out;
    }

    /**
     * The voice weight for a request's emotionSource: TEXT = words only (0), VOICE = the voice decides (1),
     * FUSED or none = the configured weight.
     */
    public static double audioWeightFor(String emotionSource, double configuredWeight) {
        if (emotionSource == null || emotionSource.isBlank()) return configuredWeight;
        return switch (emotionSource.strip().toUpperCase()) {
            case "TEXT" -> 0.0;
            case "VOICE" -> 1.0;
            case "FUSED" -> configuredWeight;
            default -> throw new IllegalArgumentException(
                    "emotionSource must be TEXT, VOICE or FUSED (got '" + emotionSource + "').");
        };
    }

    public static Fused fuse(String textLabel, double textConfidence, SpeechEmotionClient.SpeechEmotion audio, double audioWeight) {
        if (audio == null) {
            return new Fused(textLabel, textLabel, null, false, Map.of(textLabel, 1.0), "text",
                    "No voice emotion available — emotion from the words only.");
        }
        double conf = Math.max(0, Math.min(1, textConfidence));
        double w = Math.max(0, Math.min(1, audioWeight));
        List<String> labels = new ArrayList<>(new TreeSet<>(EmotionLabels.CANONICAL));
        Map<String, Double> audioScores = toCanonical(audio);

        Map<String, Double> fused = new LinkedHashMap<>();
        double spread = (1 - conf) / (labels.size() - 1);
        for (String e : labels) {
            double text = e.equals(textLabel) ? conf : spread;
            fused.put(e, (1 - w) * text + w * audioScores.getOrDefault(e, 0.0));
        }

        String label = textLabel;
        for (Map.Entry<String, Double> e : fused.entrySet()) {
            if (e.getValue() > fused.getOrDefault(label, 0.0) + 1e-9) label = e.getKey();
        }
        double topAudio = audioScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        String audioLabel = audioScores.entrySet().stream()
                .filter(e -> e.getValue() >= topAudio - 1e-9).map(Map.Entry::getKey).sorted().findFirst().orElse(null);
        boolean agree = audioScores.getOrDefault(textLabel, 0.0) >= topAudio - 1e-9;

        String note;
        if (label.equals(textLabel)) {
            note = agree
                    ? String.format("Words (%s) and voice (%s, %.0f%%) agree.", textLabel, audio.label(), audio.confidence() * 100)
                    : String.format("Words say %s (%.0f%%), the voice sounds %s (%.0f%%) — the words were more confident, kept %s.",
                            textLabel, conf * 100, audio.label(), audio.confidence() * 100, textLabel);
        } else {
            note = String.format("The voice (%s, %.0f%%) was more confident than the words (%s, %.0f%%) — emotion taken from the voice: %s.",
                    audio.label(), audio.confidence() * 100, textLabel, conf * 100, label);
        }
        return new Fused(label, textLabel, audioLabel, agree, fused, "text+audio", note);
    }
}
