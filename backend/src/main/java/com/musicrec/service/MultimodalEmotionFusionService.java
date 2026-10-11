package com.musicrec.service;

import com.musicrec.dto.AudioEmotionProfileDTO;
import com.musicrec.dto.EmotionMetadata;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Explainable fusion of text-based emotion classification with the
 * audio-based acoustic arousal signal (req. #2: "combine audio-based and
 * text-based evidence ... using a clear and explainable approach").
 *
 * Deliberately conservative: acoustic arousal never overrides the
 * text-derived primary_emotion category (voice alone can't reliably signal
 * valence — see AudioEmotionProfileDTO's documented limitation). It only
 * nudges the intensity toward the audio signal when they disagree, and
 * always records why in fusionNote so the adjustment is auditable rather
 * than silent.
 */
@Service
public class MultimodalEmotionFusionService {

    private static final double HIGH_AROUSAL_FLOOR = 0.6;
    private static final double LOW_AROUSAL_CEILING = 0.4;
    private static final double MIN_CONFIDENCE_TO_FUSE = 0.35;

    public EmotionMetadata fuse(EmotionMetadata textEmotion, AudioEmotionProfileDTO audioProfile) {
        textEmotion.setAudioEmotionProfile(audioProfile);

        if (audioProfile == null || audioProfile.getConfidence() < MIN_CONFIDENCE_TO_FUSE
                || "UNKNOWN".equals(audioProfile.getArousalLevel())) {
            textEmotion.setFusionNote("Audio arousal signal was too low-confidence to fuse — "
                    + "intensity is text-based only.");
            return textEmotion;
        }

        double original = textEmotion.getIntensity();
        if ("HIGH".equals(audioProfile.getArousalLevel()) && original < HIGH_AROUSAL_FLOOR) {
            double fused = (original + audioProfile.getArousalScore()) / 2.0;
            textEmotion.setIntensity(round2(fused));
            textEmotion.setFusionNote(String.format(
                    "Text-based intensity (%.2f) was raised toward the audio-based high-arousal signal "
                    + "(%.2f) — the voice sounded more vocally intense than the words alone suggested.",
                    original, audioProfile.getArousalScore()));
        } else if ("LOW".equals(audioProfile.getArousalLevel()) && original > LOW_AROUSAL_CEILING
                && isHighArousalEmotion(textEmotion.getPrimaryEmotionSafe())) {
            textEmotion.setFusionNote(String.format(
                    "Text suggested a high-arousal emotion (%s) but the voice sounded calm/low-energy — "
                    + "audio and text signals disagree on intensity. Keeping the text-based classification "
                    + "as primary (audio alone can't reliably indicate category), intensity left unchanged (%.2f).",
                    textEmotion.getPrimaryEmotionSafe(), original));
        } else {
            textEmotion.setFusionNote("Audio and text signals were consistent — no intensity adjustment needed.");
        }
        return textEmotion;
    }

    /**
     * Fuses the speech-emotion model's prediction from the voice with the text emotion
     * (SpeechEmotionFusion). When the voice wins, the text label is kept as a secondary emotion so
     * retrieval still considers it; the reason is appended to fusionNote either way.
     *
     * @param textConfidence confidence attributed to the text label (the text analysis has no per-call confidence)
     * @param audioWeight    0 = words only, 1 = voice only
     */
    public EmotionMetadata fuseSpeechEmotion(EmotionMetadata emotion, com.musicrec.audio.SpeechEmotionClient.SpeechEmotion voice,
                                             double textConfidence, double audioWeight) {
        String textLabel = emotion.getPrimaryEmotionSafe();
        com.musicrec.audio.SpeechEmotionFusion.Fused fused =
                com.musicrec.audio.SpeechEmotionFusion.fuse(textLabel, textConfidence, voice, audioWeight);
        emotion.setTextEmotion(textLabel);
        emotion.setSpeechEmotion(voice);
        emotion.setEmotionSource(fused.source());
        if (!fused.label().equals(textLabel)) {
            emotion.setPrimaryEmotion(fused.label());
            List<String> secondary = new java.util.ArrayList<>();
            secondary.add(textLabel);
            if (emotion.getSecondaryEmotions() != null) {
                emotion.getSecondaryEmotions().stream().filter(s -> !s.equals(fused.label()) && !s.equals(textLabel))
                        .forEach(secondary::add);
            }
            emotion.setSecondaryEmotions(secondary);
        }
        String previous = emotion.getFusionNote();
        emotion.setFusionNote((previous == null || previous.isBlank() ? "" : previous + " ") + fused.note());
        return emotion;
    }

    private boolean isHighArousalEmotion(String emotion) {
        return switch (emotion) {
            case "ANGER", "EXCITEMENT", "STRESS", "ANXIETY", "FRUSTRATION" -> true;
            default -> false;
        };
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
