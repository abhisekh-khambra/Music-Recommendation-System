package com.musicrec.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which speaker separation to trust when both the pitch method and the voiceprint method ran. */
class DiarizationChoiceTest {

    private SpeakerDiarizationService.DiarizationResult result(boolean reliable, String method) {
        return new SpeakerDiarizationService.DiarizationResult(List.of(), reliable, method);
    }

    @Test
    void reliableVoiceprintsWin() {
        var chosen = SpeakerDiarizationService.choose(result(true, "pitch"), result(true, "voiceprint"));
        assertThat(chosen.method()).startsWith("voiceprint");
    }

    @Test
    void reliablePitchIsUsedWhenVoiceprintsAreNot() {
        var chosen = SpeakerDiarizationService.choose(result(true, "pitch"), result(false, "voiceprint"));
        assertThat(chosen.method()).startsWith("pitch");
        assertThat(chosen.reliable()).isTrue();
    }

    @Test
    void neitherReliableKeepsVoiceprintsAndExplainsBoth() {
        var chosen = SpeakerDiarizationService.choose(result(false, "pitch says no"), result(false, "voiceprint says no"));
        assertThat(chosen.reliable()).isFalse();
        assertThat(chosen.method()).contains("pitch says no").contains("voiceprint says no");
    }

    @Test
    void withoutVoiceprintsPitchIsUsed() {
        var chosen = SpeakerDiarizationService.choose(result(true, "pitch"), null);
        assertThat(chosen.method()).isEqualTo("pitch");
    }
}
