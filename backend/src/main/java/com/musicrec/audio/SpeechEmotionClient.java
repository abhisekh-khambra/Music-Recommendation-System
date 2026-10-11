package com.musicrec.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.*;

/**
 * Client for the local speech-emotion service (ser-service/, Python): a trained speech emotion
 * recognition model that predicts emotion from the VOICE (neutral / happy / angry / sad), plus
 * per-segment speaker voiceprints for diarization. Sends the already-decoded 16 kHz PCM, so the
 * service needs no audio decoder. Runs on this machine — audio is not sent to any third party.
 *
 * Optional by design: if the service is disabled, not running or errors, analyze() returns empty
 * and callers fall back to text + acoustic-arousal analysis, saying so in their warnings.
 */
@Component
@Slf4j
public class SpeechEmotionClient {

    public static final int SAMPLE_RATE = 16000;

    /** Emotion predicted from the voice: label is one of the model's classes (neutral/happy/angry/sad). */
    public record SpeechEmotion(String label, double confidence, Map<String, Double> probabilities) {}

    /** One time range's voice emotion and speaker voiceprint; either is null when the range was too short. */
    public record SegmentAnalysis(long startMs, long endMs, SpeechEmotion emotion, double[] embedding) {}

    /** @param overall whole-clip prediction, only when no segments were requested */
    public record Result(SpeechEmotion overall, List<SegmentAnalysis> segments) {}

    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Value("${app.ser.url:http://127.0.0.1:8090}")
    private String baseUrl;

    @Value("${app.ser.enabled:true}")
    private boolean enabled;

    @Value("${app.ser.timeout-seconds:120}")
    private int timeoutSeconds;

    public SpeechEmotionClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param pcm        16 kHz mono 16-bit samples
     * @param spans      [startMs, endMs] ranges to analyse separately; empty → whole clip
     * @param embeddings also return a speaker voiceprint per range
     */
    public Optional<Result> analyze(short[] pcm, List<long[]> spans, boolean embeddings) {
        if (!enabled || pcm == null || pcm.length == 0) return Optional.empty();
        try {
            ByteBuffer buf = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short s : pcm) buf.putShort(s);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sampleRate", SAMPLE_RATE);
            body.put("pcmBase64", Base64.getEncoder().encodeToString(buf.array()));
            body.put("segments", spans.stream().map(s -> Map.of("startMs", s[0], "endMs", s[1])).toList());
            body.put("embeddings", embeddings);

            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/analyze"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Speech-emotion service returned HTTP {}: {}", resp.statusCode(), resp.body());
                return Optional.empty();
            }
            return Optional.of(parse(objectMapper.readTree(resp.body())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Speech-emotion service unavailable at {} — continuing without voice emotion. Cause: {}",
                    baseUrl, e.toString());
            return Optional.empty();
        }
    }

    private Result parse(JsonNode root) {
        List<SegmentAnalysis> segments = new ArrayList<>();
        for (JsonNode s : root.path("segments")) {
            double[] emb = null;
            if (s.path("embedding").isArray()) {
                JsonNode e = s.get("embedding");
                emb = new double[e.size()];
                for (int i = 0; i < e.size(); i++) emb[i] = e.get(i).asDouble();
            }
            segments.add(new SegmentAnalysis(s.path("startMs").asLong(), s.path("endMs").asLong(),
                    emotion(s.path("emotion")), emb));
        }
        return new Result(emotion(root.path("overall")), segments);
    }

    private SpeechEmotion emotion(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        Map<String, Double> probs = new LinkedHashMap<>();
        n.path("probabilities").fields().forEachRemaining(e -> probs.put(e.getKey(), e.getValue().asDouble()));
        return new SpeechEmotion(n.path("label").asText(), n.path("confidence").asDouble(), probs);
    }
}
