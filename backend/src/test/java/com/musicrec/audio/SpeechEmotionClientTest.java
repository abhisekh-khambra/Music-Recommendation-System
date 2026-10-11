package com.musicrec.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP client for the local speech-emotion service (ser-service), against a stub server. */
class SpeechEmotionClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private SpeechEmotionClient clientFor(String responseJson, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return client("http://127.0.0.1:" + server.getAddress().getPort(), true);
    }

    private SpeechEmotionClient client(String url, boolean enabled) {
        SpeechEmotionClient c = new SpeechEmotionClient(new ObjectMapper());
        ReflectionTestUtils.setField(c, "baseUrl", url);
        ReflectionTestUtils.setField(c, "enabled", enabled);
        ReflectionTestUtils.setField(c, "timeoutSeconds", 5);
        return c;
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsPcmAndSegmentsAndParsesPerSegmentEmotionAndEmbedding() throws Exception {
        SpeechEmotionClient c = clientFor("""
                {"overall": null, "segments": [
                  {"startMs": 0, "endMs": 2000, "embedding": [0.6, 0.8],
                   "emotion": {"label": "sad", "confidence": 0.81,
                               "probabilities": {"neutral": 0.1, "happy": 0.04, "angry": 0.05, "sad": 0.81}}},
                  {"startMs": 2000, "endMs": 2200, "emotion": null, "embedding": null}]}""", 200);

        short[] pcm = {1, -1, 300};
        var result = c.analyze(pcm, List.of(new long[]{0, 2000}, new long[]{2000, 2200}), true).orElseThrow();

        JsonNode sent = new ObjectMapper().readTree(lastBody.get());
        assertThat(sent.get("sampleRate").asInt()).isEqualTo(16000);
        assertThat(sent.get("embeddings").asBoolean()).isTrue();
        assertThat(sent.get("segments")).hasSize(2);
        byte[] raw = Base64.getDecoder().decode(sent.get("pcmBase64").asText());
        assertThat(raw).containsExactly(1, 0, -1, -1, 44, 1);   // little-endian int16

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).emotion().label()).isEqualTo("sad");
        assertThat(result.segments().get(0).emotion().confidence()).isEqualTo(0.81);
        assertThat(result.segments().get(0).embedding()).containsExactly(0.6, 0.8);
        assertThat(result.segments().get(1).emotion()).isNull();
        assertThat(result.segments().get(1).embedding()).isNull();
    }

    @Test
    void parsesTheWholeClipPrediction() throws Exception {
        SpeechEmotionClient c = clientFor("""
                {"overall": {"label": "happy", "confidence": 0.7,
                             "probabilities": {"neutral": 0.1, "happy": 0.7, "angry": 0.1, "sad": 0.1}},
                 "segments": []}""", 200);

        var result = c.analyze(new short[]{1, 2}, List.of(), false).orElseThrow();

        assertThat(result.overall().label()).isEqualTo("happy");
        assertThat(result.overall().probabilities()).containsEntry("happy", 0.7);
    }

    @Test
    void serviceErrorDegradesToEmpty() throws Exception {
        assertThat(clientFor("{\"detail\": \"boom\"}", 500).analyze(new short[]{1}, List.of(), false)).isEmpty();
    }

    @Test
    void unreachableServiceDegradesToEmpty() {
        assertThat(client("http://127.0.0.1:1", true).analyze(new short[]{1}, List.of(), false)).isEmpty();
    }

    @Test
    void disabledClientNeverCalls() throws Exception {
        SpeechEmotionClient c = clientFor("{}", 200);
        ReflectionTestUtils.setField(c, "enabled", false);
        assertThat(c.analyze(new short[]{1}, List.of(), false)).isEmpty();
        assertThat(lastBody.get()).isNull();
    }
}
