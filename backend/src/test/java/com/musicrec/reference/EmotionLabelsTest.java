package com.musicrec.reference;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmotionLabelsTest {

    @Test
    void nostalgyIsNormalizedToNostalgia() {
        assertThat(EmotionLabels.normalize("NOSTALGY")).contains("NOSTALGIA");
        assertThat(EmotionLabels.normalize(" nostalgy ")).contains("NOSTALGIA");
        assertThat(EmotionLabels.normalize("Nostalgic")).contains("NOSTALGIA");
    }

    @Test
    void canonicalLabelsPassThroughUppercased() {
        assertThat(EmotionLabels.normalize("nostalgia")).contains("NOSTALGIA");
        assertThat(EmotionLabels.normalize("CALM")).contains("CALM");
    }

    @Test
    void unrecognisedLabelsAreRejected() {
        assertThat(EmotionLabels.normalize("TENSION")).isEmpty();
        assertThat(EmotionLabels.normalize("")).isEmpty();
        assertThat(EmotionLabels.normalize(null)).isEmpty();
    }

    @Test
    void normalizeAllDropsUnknownsAndDuplicates() {
        List<String> out = EmotionLabels.normalizeAll(Arrays.asList("NOSTALGY", "NOSTALGIA", "OVERWHELMED", null, "sad"));
        assertThat(out).containsExactly("NOSTALGIA", "SADNESS");
    }
}
