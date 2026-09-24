package com.musicrec.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Converts audio (WebM/MP3/WAV/M4A) to text using Groq's Whisper endpoint.
 *
 * The transcriptionModel is injected with required=false so that the backend
 * starts even if the Groq audio API is unavailable. Text recommendations work
 * regardless; only voice input degrades gracefully with a clear error message.
 */
@Service
@Slf4j
public class SpeechToTextService {

    @Autowired(required = false)          // ← optional: backend starts without this
    private OpenAiAudioTranscriptionModel transcriptionModel;

    public SpeechToTextService() {
        // No-arg constructor required for optional field injection
    }

    /**
     * Transcribes a raw audio MultipartFile to text via Groq Whisper.
     *
     * @param audioFile audio recorded by the browser (WebM / Opus codec)
     * @return transcript string
     * @throws RuntimeException if transcription fails or model is unavailable
     */
    public String transcribe(MultipartFile audioFile) {
        if (transcriptionModel == null) {
            log.error("OpenAiAudioTranscriptionModel not available — check Groq audio config");
            throw new RuntimeException(
                "Voice transcription is temporarily unavailable. "
                + "Please use Text Input instead, or verify your Groq API key in application.yml.");
        }

        log.info("Transcribing audio: name={} size={}b type={}",
                audioFile.getOriginalFilename(),
                audioFile.getSize(),
                audioFile.getContentType());

        try {
            byte[] bytes = audioFile.getBytes();
            Resource audioResource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    String original = audioFile.getOriginalFilename();
                    return (original != null && !original.isBlank()) ? original : "recording.webm";
                }
            };

            OpenAiAudioTranscriptionOptions options = OpenAiAudioTranscriptionOptions.builder()
                    .model("whisper-large-v3")
                    .temperature(0f)
                    .responseFormat(OpenAiAudioApi.TranscriptResponseFormat.TEXT)
                    .build();

            AudioTranscriptionPrompt prompt = new AudioTranscriptionPrompt(audioResource, options);
            AudioTranscriptionResponse response = transcriptionModel.call(prompt);

            String transcript = response.getResult().getOutput();
            log.info("Transcription complete: [{}]", transcript);
            return transcript;

        } catch (IOException e) {
            log.error("Failed to read audio bytes", e);
            throw new RuntimeException("Audio file could not be read: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Whisper transcription error", e);
            throw new RuntimeException("Speech-to-text failed: " + e.getMessage(), e);
        }
    }

    /** Returns true if the Whisper transcription model is available */
    public boolean isTranscriptionAvailable() {
        return transcriptionModel != null;
    }
}
