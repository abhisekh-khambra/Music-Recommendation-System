package com.musicrec.controller;

import com.musicrec.audio.AcousticEmotionAnalysisService;
import com.musicrec.audio.AcousticFeatureExtractor;
import com.musicrec.audio.AudioTranscodingService;
import com.musicrec.audio.SpeakerDiarizationService;
import com.musicrec.exception.InvalidAudioException;
import com.musicrec.repository.ChatHistoryRepository;
import com.musicrec.repository.TrackRepository;
import com.musicrec.repository.UserRepository;
import com.musicrec.service.ConversationAnalysisService;
import com.musicrec.service.EmotionAnalysisService;
import com.musicrec.service.MultimodalEmotionFusionService;
import com.musicrec.service.MusicRecommendationService;
import com.musicrec.service.RagaSelectionService;
import com.musicrec.service.SpeakerRoleResolver;
import com.musicrec.service.SpeechToTextService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bad or empty audio must be rejected with a clear 4xx before it reaches speech-to-text:
 * a non-audio file used to surface as HTTP 500, and silence let Whisper hallucinate a
 * transcript ("you") that then produced 10 songs.
 */
class AudioInputValidationTest {

    private static final MockMultipartFile UPLOAD =
            new MockMultipartFile("audio", "clip.mp3", "audio/mpeg", new byte[]{1, 2, 3, 4});

    private final SpeechToTextService stt = mock(SpeechToTextService.class);
    private final AudioTranscodingService transcoder = mock(AudioTranscodingService.class);
    private final AcousticFeatureExtractor extractor = new AcousticFeatureExtractor();

    private RecommendationController voiceController() {
        return new RecommendationController(
                mock(EmotionAnalysisService.class), mock(MusicRecommendationService.class), stt,
                mock(ChatHistoryRepository.class), mock(UserRepository.class), mock(TrackRepository.class),
                transcoder, extractor, new AcousticEmotionAnalysisService(), mock(MultimodalEmotionFusionService.class));
    }

    private ConversationController conversationController() {
        return new ConversationController(
                stt, mock(ConversationAnalysisService.class), mock(SpeakerRoleResolver.class),
                mock(EmotionAnalysisService.class), mock(RagaSelectionService.class), mock(MusicRecommendationService.class),
                transcoder, extractor, new AcousticEmotionAnalysisService(), new SpeakerDiarizationService(extractor),
                mock(ChatHistoryRepository.class), mock(UserRepository.class));
    }

    @Test
    void voiceRejectsUndecodableFileBeforeSpeechToText() {
        when(transcoder.toPcm16Mono16k(any(), anyString()))
                .thenThrow(new AudioTranscodingService.UndecodableAudioException("Invalid data found when processing input"));

        assertThatThrownBy(() -> voiceController().recommendByVoice(UPLOAD, null, null))
                .isInstanceOf(InvalidAudioException.class);
        verify(stt, never()).transcribe(any());
    }

    @Test
    void voiceRejectsSilenceBeforeSpeechToText() {
        when(transcoder.toPcm16Mono16k(any(), anyString())).thenReturn(new short[16_000 * 3]);

        assertThatThrownBy(() -> voiceController().recommendByVoice(UPLOAD, null, null))
                .isInstanceOf(InvalidAudioException.class)
                .hasMessageContaining("speech");
        verify(stt, never()).transcribe(any());
    }

    @Test
    void voiceStillTranscribesWhenFfmpegIsUnavailable() {
        when(transcoder.toPcm16Mono16k(any(), anyString()))
                .thenThrow(new AudioTranscodingService.TranscodingUnavailableException("ffmpeg not installed", null));

        catchThrowable(() -> voiceController().recommendByVoice(UPLOAD, null, null)); // downstream mocks are empty
        verify(stt).transcribe(any());
    }

    @Test
    void conversationRejectsUndecodableFileBeforeSpeechToText() {
        when(transcoder.toPcm16Mono16k(any(), anyString()))
                .thenThrow(new AudioTranscodingService.UndecodableAudioException("Invalid data found when processing input"));

        assertThatThrownBy(() -> conversationController().analyzeConversation(UPLOAD, null, null))
                .isInstanceOf(InvalidAudioException.class);
        verify(stt, never()).transcribeWithSegments(any());
    }

    @Test
    void conversationRejectsSilenceBeforeSpeechToText() {
        when(transcoder.toPcm16Mono16k(any(), anyString())).thenReturn(new short[16_000 * 3]);

        assertThatThrownBy(() -> conversationController().analyzeConversation(UPLOAD, null, null))
                .isInstanceOf(InvalidAudioException.class)
                .hasMessageContaining("speech");
        verify(stt, never()).transcribeWithSegments(any());
    }
}
