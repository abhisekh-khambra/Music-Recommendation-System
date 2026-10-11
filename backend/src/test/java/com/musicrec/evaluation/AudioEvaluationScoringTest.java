package com.musicrec.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudioEvaluationScoringTest {

    private static final String C = "HEALTH_CONSULTANT", P = "PATIENT";

    @Test
    void roleAccuracyUsesTheBestClusterToRoleMapping() {
        // Cluster ids are arbitrary: 1,0,1,0 against C,P,C,P is a perfect split.
        assertThat(AudioEvaluationScoring.roleAccuracy(new int[]{1, 0, 1, 0}, List.of(C, P, C, P))).isEqualTo(1.0);
        assertThat(AudioEvaluationScoring.roleAccuracy(new int[]{0, 0, 1, 0}, List.of(C, P, C, P))).isEqualTo(0.75);
        // Everything in one cluster: only the majority role can be right.
        assertThat(AudioEvaluationScoring.roleAccuracy(new int[]{0, 0, 0, 0}, List.of(C, P, C, P))).isEqualTo(0.5);
    }

    @Test
    void goldEmotionsCollapseOntoTheVoiceModelsFourClasses() {
        assertThat(AudioEvaluationScoring.voiceClass("SADNESS")).isEqualTo("sad");
        assertThat(AudioEvaluationScoring.voiceClass("MELANCHOLY")).isEqualTo("sad");
        assertThat(AudioEvaluationScoring.voiceClass("EXCITEMENT")).isEqualTo("happy");
        assertThat(AudioEvaluationScoring.voiceClass("FRUSTRATION")).isEqualTo("angry");
        assertThat(AudioEvaluationScoring.voiceClass("RELAXATION")).isEqualTo("neutral");
        // No matching class in the voice model — excluded from voice-only accuracy, not counted wrong.
        assertThat(AudioEvaluationScoring.voiceClass("NOSTALGIA")).isNull();
        assertThat(AudioEvaluationScoring.voiceClass("ANXIETY")).isNull();
    }

    @Test
    void accuracySkipsUnlabelledPairs() {
        assertThat(AudioEvaluationScoring.accuracy(List.of("sad", "happy", "sad"), java.util.Arrays.asList("sad", null, "angry")))
                .isEqualTo(0.5);
        assertThat(AudioEvaluationScoring.accuracy(List.of("x"), java.util.Collections.singletonList(null))).isNull();
    }
}
