package com.musicrec.controller;

import com.musicrec.audio.AcousticEmotionAnalysisService;
import com.musicrec.audio.AcousticFeatureExtractor;
import com.musicrec.audio.AudioTranscodingService;
import com.musicrec.audio.SpeakerDiarizationService;
import com.musicrec.dto.ConversationRecommendationResponse;
import com.musicrec.dto.ConversationTurnDTO;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.RagaSelectionDTO;
import com.musicrec.dto.RoleReassignmentRequest;
import com.musicrec.dto.TrackDTO;
import com.musicrec.entity.Track;
import com.musicrec.repository.ChatHistoryRepository;
import com.musicrec.repository.UserRepository;
import com.musicrec.service.ConversationAnalysisService;
import com.musicrec.service.ConversationPipelineService;
import com.musicrec.service.EmotionAnalysisService;
import com.musicrec.service.MusicRecommendationService;
import com.musicrec.service.RagaSelectionService;
import com.musicrec.service.SpeakerRoleResolver;
import com.musicrec.service.SpeechToTextService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies req. #3's user-role-correction path: POST /reassign-roles takes
 * client-corrected speaker roles (no audio re-upload) and recomputes
 * patient-text / emotion / recommendations from them, never trusting a
 * pre-baked server-side guess over the user's explicit correction.
 */
class ConversationControllerReassignRolesTest {

    private Track hindiTrack(long id) {
        Track t = new Track();
        t.setId(id);
        t.setTitle("Hindi Track " + id);
        t.setArtist("Artist");
        t.setLanguage("Hindi");
        t.setPrimaryEmotion("CALM");
        return t;
    }

    private ConversationController newController(
            EmotionAnalysisService emotionAnalysisService,
            ConversationAnalysisService conversationAnalysisService,
            RagaSelectionService ragaSelectionService,
            MusicRecommendationService recommendationService,
            ChatHistoryRepository chatHistoryRepository,
            UserRepository userRepository) {

        ConversationPipelineService pipeline = new ConversationPipelineService(
                conversationAnalysisService,
                emotionAnalysisService,
                ragaSelectionService,
                recommendationService,
                mock(AcousticFeatureExtractor.class),
                mock(AcousticEmotionAnalysisService.class),
                chatHistoryRepository,
                userRepository);
        ReflectionTestUtils.setField(pipeline, "topNResults", 10);

        ConversationController controller = new ConversationController(
                mock(SpeechToTextService.class),
                conversationAnalysisService,
                mock(SpeakerRoleResolver.class),
                mock(AudioTranscodingService.class),
                mock(AcousticFeatureExtractor.class),
                mock(SpeakerDiarizationService.class),
                pipeline, mock(com.musicrec.audio.SpeechEmotionClient.class));
        return controller;
    }

    @Test
    void reassignRolesRecomputesPatientTextFromUserCorrectedRolesOnly() {
        EmotionAnalysisService emotionAnalysisService = mock(EmotionAnalysisService.class);
        ConversationAnalysisService conversationAnalysisService = mock(ConversationAnalysisService.class);
        RagaSelectionService ragaSelectionService = mock(RagaSelectionService.class);
        MusicRecommendationService recommendationService = mock(MusicRecommendationService.class);
        ChatHistoryRepository chatHistoryRepository = mock(ChatHistoryRepository.class);
        UserRepository userRepository = mock(UserRepository.class);

        EmotionMetadata emotion = new EmotionMetadata();
        emotion.setPrimaryEmotion("CALM");
        emotion.setIntensity(0.4);
        emotion.setLanguage("English");
        when(emotionAnalysisService.analyze(anyString())).thenReturn(emotion);
        when(emotionAnalysisService.analyzeBatch(anyList())).thenReturn(List.of());
        when(conversationAnalysisService.buildVariability(anyList())).thenReturn(
                com.musicrec.dto.EmotionalVariabilityDTO.builder().segments(List.of())
                        .emotionDistribution(new java.util.LinkedHashMap<>()).build());
        when(conversationAnalysisService.withDistributionEmotions(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        when(ragaSelectionService.selectRaga(any(EmotionMetadata.class), any(), eq(true)))
                .thenReturn(RagaSelectionDTO.builder().raga("Yaman").warnings(List.of()).build());
        when(ragaSelectionService.ragasPerEmotion(any(), eq(true)))
                .thenReturn(new RagaSelectionService.RagaPerEmotion(java.util.Map.of(), List.of()));

        TrackDTO trackDTO = TrackDTO.from(hindiTrack(1L));
        when(recommendationService.recommend(any(EmotionMetadata.class), anyString(), eq(true), any()))
                .thenReturn(new MusicRecommendationService.RankingResult(List.of(trackDTO), "message"));

        ConversationController controller = newController(
                emotionAnalysisService, conversationAnalysisService, ragaSelectionService,
                recommendationService, chatHistoryRepository, userRepository);

        // Original automatic split (hypothetically) had turn 0 as HEALTH_CONSULTANT — the
        // user corrects it to PATIENT, which must change what text drives the emotion analysis.
        RoleReassignmentRequest request = new RoleReassignmentRequest();
        request.setSessionId("session-1");
        RoleReassignmentRequest.TurnInput t0 = new RoleReassignmentRequest.TurnInput();
        t0.setOrder(0); t0.setSpeakerRole("PATIENT"); t0.setText("I feel very low today"); t0.setStartMs(0); t0.setEndMs(1000);
        RoleReassignmentRequest.TurnInput t1 = new RoleReassignmentRequest.TurnInput();
        t1.setOrder(1); t1.setSpeakerRole("HEALTH_CONSULTANT"); t1.setText("Tell me more"); t1.setStartMs(1000); t1.setEndMs(2000);
        request.setTurns(List.of(t0, t1));

        ResponseEntity<ConversationRecommendationResponse> response = controller.reassignRoles(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ConversationRecommendationResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getPatientText()).isEqualTo("I feel very low today");
        assertThat(body.getTurns()).hasSize(2);
        assertThat(body.getTurns().get(0).speakerRole()).isEqualTo(ConversationTurnDTO.PATIENT);
        assertThat(body.getTurns().get(1).speakerRole()).isEqualTo(ConversationTurnDTO.HEALTH_CONSULTANT);
        assertThat(body.isSpeakerAssignmentUncertain()).isFalse();
        assertThat(body.getWarnings()).anyMatch(w -> w.contains("manually corrected"));
        assertThat(body.getRecommendations()).extracting(TrackDTO::getLanguage).containsOnly("Hindi");
    }
}
