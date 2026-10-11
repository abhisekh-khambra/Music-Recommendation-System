package com.musicrec.audio;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Late fusion of the text emotion with the speech-emotion model's prediction from the voice. */
class SpeechEmotionFusionTest {

    private SpeechEmotionClient.SpeechEmotion audio(String label, double conf) {
        Map<String, Double> p = new java.util.LinkedHashMap<>(Map.of("neutral", 0.0, "happy", 0.0, "angry", 0.0, "sad", 0.0));
        double rest = (1 - conf) / 3;
        p.replaceAll((k, v) -> k.equals(label) ? conf : rest);
        return new SpeechEmotionClient.SpeechEmotion(label, conf, p);
    }

    @Test
    void voiceLabelsMapOntoCanonicalEmotions() {
        assertThat(SpeechEmotionFusion.toCanonical(audio("sad", 1.0))).containsEntry("SADNESS", 1.0);
        assertThat(SpeechEmotionFusion.toCanonical(audio("happy", 1.0))).containsEntry("JOY", 1.0);
        assertThat(SpeechEmotionFusion.toCanonical(audio("angry", 1.0))).containsEntry("ANGER", 1.0);
        // Neutral speech is split between the two calm states the text model uses.
        assertThat(SpeechEmotionFusion.toCanonical(audio("neutral", 1.0)))
                .containsEntry("CALM", 0.5).containsEntry("RELAXATION", 0.5);
    }

    @Test
    void confidentWordsAreKeptWhenTheVoiceIsLessSure() {
        SpeechEmotionFusion.Fused f = SpeechEmotionFusion.fuse("NOSTALGIA", 0.9, audio("sad", 0.6), 0.5);
        assertThat(f.label()).isEqualTo("NOSTALGIA");
        assertThat(f.audioLabel()).isEqualTo("SADNESS");
        assertThat(f.agree()).isFalse();
        assertThat(f.source()).isEqualTo("text+audio");
    }

    @Test
    void aConfidentVoiceDecidesWhenTheWordsAreAmbiguous() {
        // "I'm fine" said in a sad voice: the text model is unsure, the voice is clear.
        SpeechEmotionFusion.Fused f = SpeechEmotionFusion.fuse("CALM", 0.4, audio("sad", 0.9), 0.5);
        assertThat(f.label()).isEqualTo("SADNESS");
        assertThat(f.note()).contains("voice");
    }

    @Test
    void agreementIsRecognisedIncludingTheCalmFamily() {
        assertThat(SpeechEmotionFusion.fuse("SADNESS", 0.8, audio("sad", 0.8), 0.5).agree()).isTrue();
        assertThat(SpeechEmotionFusion.fuse("RELAXATION", 0.8, audio("neutral", 0.8), 0.5).agree()).isTrue();
        assertThat(SpeechEmotionFusion.fuse("RELAXATION", 0.8, audio("neutral", 0.8), 0.5).label()).isEqualTo("RELAXATION");
    }

    @Test
    void withoutAudioTheTextLabelIsUnchanged() {
        SpeechEmotionFusion.Fused f = SpeechEmotionFusion.fuse("JOY", 0.3, null, 0.5);
        assertThat(f.label()).isEqualTo("JOY");
        assertThat(f.audioLabel()).isNull();
        assertThat(f.source()).isEqualTo("text");
    }

    @Test
    void zeroAudioWeightIsTextOnlyAndFullWeightIsAudioOnly() {
        assertThat(SpeechEmotionFusion.fuse("JOY", 0.3, audio("sad", 0.99), 0.0).label()).isEqualTo("JOY");
        assertThat(SpeechEmotionFusion.fuse("JOY", 0.99, audio("sad", 0.6), 1.0).label()).isEqualTo("SADNESS");
    }

    @Test
    void fusedScoresSumToOne() {
        SpeechEmotionFusion.Fused f = SpeechEmotionFusion.fuse("STRESS", 0.7, audio("angry", 0.8), 0.5);
        assertThat(f.scores().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(1e-6));
    }
}
