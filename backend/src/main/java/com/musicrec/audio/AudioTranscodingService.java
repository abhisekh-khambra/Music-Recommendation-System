package com.musicrec.audio;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Decodes whatever audio format the browser/recorder produced (WebM/Opus,
 * MP3, M4A, WAV, ...) into raw 16kHz mono 16-bit PCM, which {@link AcousticFeatureExtractor}
 * can analyze directly.
 *
 * There is no pure-Java decoder for WebM/Opus or MP3, so this shells out to
 * the external `ffmpeg` binary (a documented prerequisite — see README) rather
 * than pulling in a ~300MB native-binary-bundling Maven dependency. This is a
 * format-conversion step only: no ML model is involved here or anywhere in the
 * acoustic analysis pipeline downstream of it.
 */
@Service
@Slf4j
public class AudioTranscodingService {

    public static final int TARGET_SAMPLE_RATE = 16_000;

    @Value("${app.audio.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${app.audio.transcode-timeout-seconds:30}")
    private int timeoutSeconds;

    /** Thrown when ffmpeg is missing or the input audio can't be decoded. Callers should degrade gracefully. */
    public static class TranscodingUnavailableException extends RuntimeException {
        public TranscodingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * ffmpeg ran but could not decode the input — the upload is not valid audio. Unlike a missing
     * ffmpeg (which is degraded around), this is the client's fault and should be reported as such.
     */
    public static class UndecodableAudioException extends TranscodingUnavailableException {
        public UndecodableAudioException(String message) {
            super(message, null);
        }
    }

    /**
     * Decodes raw audio bytes (any ffmpeg-supported container/codec) into
     * signed 16-bit little-endian mono PCM samples at {@link #TARGET_SAMPLE_RATE}.
     */
    public short[] toPcm16Mono16k(byte[] inputBytes, String originalFilename) {
        Path inputFile = null;
        Path outputFile = null;
        try {
            String suffix = guessSuffix(originalFilename);
            inputFile = Files.createTempFile("moodtunes-audio-in-", suffix);
            outputFile = Files.createTempFile("moodtunes-audio-out-", ".pcm");
            Files.write(inputFile, inputBytes);

            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath, "-y",
                    "-i", inputFile.toAbsolutePath().toString(),
                    "-ac", "1",
                    "-ar", String.valueOf(TARGET_SAMPLE_RATE),
                    "-f", "s16le",
                    outputFile.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Drain stdout/stderr so ffmpeg never blocks on a full pipe buffer.
            String ffmpegOutput = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TranscodingUnavailableException(
                        "Audio decoding timed out after " + timeoutSeconds + "s", null);
            }
            if (process.exitValue() != 0) {
                String[] lines = ffmpegOutput.strip().split("\\R");
                throw new UndecodableAudioException("ffmpeg could not decode the uploaded audio (exit code "
                        + process.exitValue() + "): " + lines[lines.length - 1]);
            }

            byte[] pcmBytes = Files.readAllBytes(outputFile);
            return toShortArray(pcmBytes);

        } catch (IOException e) {
            throw new TranscodingUnavailableException(
                    "Could not run ffmpeg for audio decoding — is it installed and on PATH? "
                    + "Set app.audio.ffmpeg-path if it's installed elsewhere.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TranscodingUnavailableException("Audio decoding was interrupted", e);
        } finally {
            deleteQuietly(inputFile);
            deleteQuietly(outputFile);
        }
    }

    private String guessSuffix(String originalFilename) {
        if (originalFilename == null) return ".webm";
        int dot = originalFilename.lastIndexOf('.');
        return (dot >= 0 && dot < originalFilename.length() - 1) ? originalFilename.substring(dot) : ".webm";
    }

    private short[] toShortArray(byte[] pcmBytes) {
        int sampleCount = pcmBytes.length / 2;
        short[] samples = new short[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int lo = pcmBytes[2 * i] & 0xFF;
            int hi = pcmBytes[2 * i + 1];
            samples[i] = (short) ((hi << 8) | lo); // little-endian s16le
        }
        return samples;
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Could not delete temp audio file {}: {}", path, e.getMessage());
        }
    }
}
