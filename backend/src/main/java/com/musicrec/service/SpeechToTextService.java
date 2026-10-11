package com.musicrec.service;

import com.musicrec.dto.TranscriptSegmentDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    @Autowired(required = false)
    private OpenAiAudioApi audioApi;

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
            if (transcript != null) {
                transcript = transcript.trim();
            }
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

    /** Result of a segmented transcription: full text plus Whisper's own time-aligned segments. */
    public record SegmentedTranscript(String fullText, List<TranscriptSegmentDTO> segments) {}

    /**
     * Transcribes audio via Groq Whisper's verbose_json response format, which
     * returns real per-segment start/end timestamps decoded from the audio
     * itself — used by the conversation pipeline so speaker diarization can
     * align acoustic clusters to transcript text by time, instead of
     * re-splitting the transcript with an LLM guess.
     *
     * Falls back to a single whole-clip segment (via the plain-text endpoint)
     * if the lower-level audio API isn't available for any reason, so the
     * conversation pipeline can still degrade to text-only analysis rather
     * than failing outright.
     */
    public SegmentedTranscript transcribeWithSegments(MultipartFile audioFile) {
        if (audioApi == null) {
            log.warn("OpenAiAudioApi not available — falling back to whole-clip transcription without segments");
            return fallbackSingleSegment(audioFile);
        }

        try {
            byte[] bytes = audioFile.getBytes();
            String filename = (audioFile.getOriginalFilename() != null && !audioFile.getOriginalFilename().isBlank())
                    ? audioFile.getOriginalFilename() : "recording.webm";

            OpenAiAudioApi.TranscriptionRequest request = OpenAiAudioApi.TranscriptionRequest.builder()
                    .file(bytes)
                    .model("whisper-large-v3")
                    .temperature(0f)
                    .responseFormat(OpenAiAudioApi.TranscriptResponseFormat.VERBOSE_JSON)
                    .build();

            ResponseEntity<OpenAiAudioApi.StructuredResponse> response =
                    audioApi.createTranscription(request, OpenAiAudioApi.StructuredResponse.class);

            OpenAiAudioApi.StructuredResponse body = response.getBody();
            if (body == null) {
                throw new RuntimeException("Empty transcription response from Groq Whisper");
            }

            List<TranscriptSegmentDTO> segments = new ArrayList<>();
            if (body.segments() != null) {
                int order = 0;
                for (OpenAiAudioApi.StructuredResponse.Segment seg : body.segments()) {
                    String text = seg.text() == null ? "" : seg.text().trim();
                    if (text.isBlank()) continue;
                    long startMs = Math.round((seg.start() == null ? 0f : seg.start()) * 1000);
                    long endMs = Math.round((seg.end() == null ? 0f : seg.end()) * 1000);
                    segments.add(new TranscriptSegmentDTO(order++, startMs, endMs, text));
                }
            }

            String fullText = body.text() != null ? body.text().trim() : "";
            log.info("Segmented transcription complete: {} segments, {} chars", segments.size(), fullText.length());

            if (segments.isEmpty() && !fullText.isBlank()) {
                segments.add(new TranscriptSegmentDTO(0, 0, 0, fullText));
            }

            return new SegmentedTranscript(fullText, segments);

        } catch (IOException e) {
            log.error("Failed to read audio bytes for segmented transcription", e);
            throw new RuntimeException("Audio file could not be read: " + e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Segmented transcription failed, falling back to whole-clip transcription. Cause: {}", e.getMessage());
            return fallbackSingleSegment(audioFile);
        }
    }

    private SegmentedTranscript fallbackSingleSegment(MultipartFile audioFile) {
        String text = transcribe(audioFile);
        List<TranscriptSegmentDTO> segments = (text == null || text.isBlank())
                ? List.of()
                : List.of(new TranscriptSegmentDTO(0, 0, 0, text));
        return new SegmentedTranscript(text, segments);
    }
}
