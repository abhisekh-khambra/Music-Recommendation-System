package com.musicrec.service;

import com.musicrec.audio.AcousticFeatureExtractor;
import com.musicrec.audio.SpeakerDiarizationService;
import com.musicrec.dto.ConversationTurnDTO;
import com.musicrec.dto.TranscriptSegmentDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpeakerRoleResolverTest {

    private final SpeakerRoleResolver resolver = new SpeakerRoleResolver();

    private SpeakerDiarizationService.DiarizedSegment segment(int order, int clusterId) {
        TranscriptSegmentDTO seg = new TranscriptSegmentDTO(order, order * 1000L, (order + 1) * 1000L, "text " + order);
        return new SpeakerDiarizationService.DiarizedSegment(seg, AcousticFeatureExtractor.Summary.EMPTY, clusterId);
    }

    @Test
    void reliableDiarizationAssignsClusterRoleByMajorityVoteOfTextLabels() {
        // Cluster 0 (segments 0,2) mostly labeled PATIENT by text; cluster 1 (segments 1,3) mostly HEALTH_CONSULTANT.
        var diarized = List.of(segment(0, 0), segment(1, 1), segment(2, 0), segment(3, 1));
        var diarization = new SpeakerDiarizationService.DiarizationResult(diarized, true, "reliable clustering");
        var textRoles = List.of(
                ConversationTurnDTO.PATIENT, ConversationTurnDTO.HEALTH_CONSULTANT,
                ConversationTurnDTO.PATIENT, ConversationTurnDTO.HEALTH_CONSULTANT);

        SpeakerRoleResolver.Resolution resolution = resolver.resolve(diarization, textRoles);

        assertThat(resolution.turns()).hasSize(4);
        assertThat(resolution.turns().get(0).speakerRole()).isEqualTo(ConversationTurnDTO.PATIENT);
        assertThat(resolution.turns().get(1).speakerRole()).isEqualTo(ConversationTurnDTO.HEALTH_CONSULTANT);
        assertThat(resolution.turns().get(2).speakerRole()).isEqualTo(ConversationTurnDTO.PATIENT);
        assertThat(resolution.turns().get(3).speakerRole()).isEqualTo(ConversationTurnDTO.HEALTH_CONSULTANT);
        assertThat(resolution.warnings()).isEmpty();
    }

    @Test
    void unreliableDiarizationFallsBackToPerSegmentTextRolesAndWarns() {
        var diarized = List.of(segment(0, 0), segment(1, 0)); // both in same cluster = degenerate
        var diarization = new SpeakerDiarizationService.DiarizationResult(diarized, false, "not reliable");
        var textRoles = List.of(ConversationTurnDTO.PATIENT, ConversationTurnDTO.HEALTH_CONSULTANT);

        SpeakerRoleResolver.Resolution resolution = resolver.resolve(diarization, textRoles);

        assertThat(resolution.turns().get(0).speakerRole()).isEqualTo(ConversationTurnDTO.PATIENT);
        assertThat(resolution.turns().get(1).speakerRole()).isEqualTo(ConversationTurnDTO.HEALTH_CONSULTANT);
        assertThat(resolution.warnings()).isNotEmpty();
    }

    @Test
    void preservesTimestampsFromTheOriginalSegments() {
        var diarized = List.of(segment(0, 0));
        var diarization = new SpeakerDiarizationService.DiarizationResult(diarized, false, "n/a");

        SpeakerRoleResolver.Resolution resolution = resolver.resolve(diarization, List.of(ConversationTurnDTO.PATIENT));

        ConversationTurnDTO turn = resolution.turns().get(0);
        assertThat(turn.startMs()).isEqualTo(0L);
        assertThat(turn.endMs()).isEqualTo(1000L);
    }
}
