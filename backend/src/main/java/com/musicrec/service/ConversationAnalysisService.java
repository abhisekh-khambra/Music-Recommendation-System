package com.musicrec.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.ConversationAnalysisDTO;
import com.musicrec.dto.ConversationTurnDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Speaker segmentation for the conversation pipeline.
 *
 * IMPORTANT — this is TEXT-based turn segmentation, not audio speaker
 * diarization. Groq's Whisper endpoint (used by SpeechToTextService) returns
 * plain text, not per-speaker audio segments, and there is no audio
 * diarization model (e.g. pyannote.audio) wired into this Spring Boot stack.
 * Instead, the transcript is segmented into turns and each turn is labeled
 * Health Consultant vs. Patient using conversational role cues (who asks
 * questions vs. who describes their own feelings/experience) — a clean,
 * explainable mechanism that satisfies the two-speaker requirement without
 * adding new audio-processing infrastructure. True audio diarization is a
 * documented gap, not a silent shortcut — see the README limitations section.
 */
@Service
@Slf4j
public class ConversationAnalysisService {

    private final ChatClient conversationChatClient;
    private final ObjectMapper objectMapper;

    private static final Set<String> NEGATIVE_EMOTIONS = Set.of(
            "SADNESS", "MELANCHOLY", "ANGER", "STRESS", "ANXIETY", "FRUSTRATION");
    private static final Set<String> POSITIVE_EMOTIONS = Set.of(
            "JOY", "EXCITEMENT", "LOVE");
    private static final Set<String> NEUTRAL_CALM_EMOTIONS = Set.of(
            "RELAXATION", "CALM", "NOSTALGIA");

    private static final String TURN_SEGMENTATION_TEMPLATE = """
            The following is a transcribed spoken conversation between a Health Consultant and a
            Patient. Speaker roles are NOT marked in the raw transcript. Segment it into individual
            turns, in original order, and label each turn's speaker.

            Labeling guidance:
            - The Health Consultant typically asks questions, offers guidance, or responds in a
              professional/supportive register.
            - The Patient typically describes their own feelings, symptoms, sleep, stress, or
              day-to-day experience in the first person.
            - If a turn's speaker is ambiguous, use the question/answer alternation pattern of the
              conversation to decide.
            - Do not merge multiple back-and-forth exchanges into one turn.
            - Do not add, remove, or paraphrase words from the transcript — split it, don't rewrite it.

            Raw transcript:
            "%s"

            Return ONLY this JSON — no markdown, no extra text:
            {
              "turns": [
                {"speaker": "HEALTH_CONSULTANT", "text": "..."},
                {"speaker": "PATIENT", "text": "..."}
              ]
            }
            """;

    public ConversationAnalysisService(
            @Qualifier("conversationChatClient") ChatClient conversationChatClient,
            ObjectMapper objectMapper) {
        this.conversationChatClient = conversationChatClient;
        this.objectMapper = objectMapper;
    }

    /** Result of turn segmentation: labeled turns, the isolated patient text, and any degradation warnings. */
    public record SegmentationResult(List<ConversationTurnDTO> turns, String patientText, List<String> warnings) {}

    public SegmentationResult segment(String transcript) {
        List<String> warnings = new ArrayList<>();

        try {
            String prompt = TURN_SEGMENTATION_TEMPLATE.formatted(escapeJson(transcript));
            String rawJson = conversationChatClient.prompt().user(prompt).call().content();
            JsonNode root = objectMapper.readTree(extractJsonBlock(rawJson));

            List<ConversationTurnDTO> turns = new ArrayList<>();
            int order = 0;
            for (JsonNode turnNode : root.path("turns")) {
                String speaker = normalizeSpeaker(turnNode.path("speaker").asText(""));
                String text = turnNode.path("text").asText("").trim();
                if (!text.isBlank()) {
                    turns.add(new ConversationTurnDTO(order++, speaker, text));
                }
            }

            if (turns.isEmpty()) {
                return fallback(transcript, "Speaker segmentation returned no turns — analyzed the full transcript as a single speaker.");
            }

            String patientText = String.join(" ", turns.stream()
                    .filter(t -> ConversationTurnDTO.PATIENT.equals(t.speakerRole()))
                    .map(ConversationTurnDTO::text)
                    .toList());

            if (patientText.isBlank()) {
                warnings.add("Could not isolate any Patient turns from the transcript — the full transcript was used for conversation analysis instead.");
                patientText = transcript;
            }

            return new SegmentationResult(turns, patientText, warnings);

        } catch (Exception ex) {
            log.warn("Conversation turn segmentation failed — treating transcript as a single speaker. Cause: {}", ex.getMessage());
            return fallback(transcript, "Automatic speaker segmentation was unavailable — the full transcript was analyzed as a single speaker turn.");
        }
    }

    private SegmentationResult fallback(String transcript, String warning) {
        List<ConversationTurnDTO> single = List.of(new ConversationTurnDTO(0, ConversationTurnDTO.PATIENT, transcript));
        return new SegmentationResult(single, transcript, new ArrayList<>(List.of(warning)));
    }

    /**
     * Builds the non-diagnostic analysis panel in pure code from an already-computed
     * EmotionMetadata (produced by the existing EmotionAnalysisService over the patient
     * text) — no LLM call here, so the phrasing can never drift into diagnostic language.
     */
    public ConversationAnalysisDTO buildAnalysis(com.musicrec.dto.EmotionMetadata emotion) {
        String primary = emotion.getPrimaryEmotionSafe();
        List<String> secondary = emotion.getSecondaryEmotions() == null ? List.of() : emotion.getSecondaryEmotions();

        Set<String> allStates = new java.util.HashSet<>();
        allStates.add(primary.toUpperCase());
        secondary.forEach(s -> { if (s != null) allStates.add(s.toUpperCase()); });

        String sentiment = deriveSentiment(allStates);

        return ConversationAnalysisDTO.builder()
                .sentiment(sentiment)
                .primaryEmotionalState(primary)
                .secondaryEmotionalStates(secondary)
                .emotionalIntensity(emotion.getIntensity())
                .stressCuesDetected(allStates.contains("STRESS") || allStates.contains("FRUSTRATION"))
                .anxietyCuesDetected(allStates.contains("ANXIETY"))
                .sadnessCuesDetected(allStates.contains("SADNESS") || allStates.contains("MELANCHOLY"))
                .relaxationCuesDetected(allStates.contains("RELAXATION") || allStates.contains("CALM"))
                .moodKeywords(emotion.getMoodKeywords() == null ? List.of() : emotion.getMoodKeywords())
                .detectedLanguage(emotion.getLanguageSafe())
                .contextSummary(buildContextSummary(primary, secondary, emotion.getIntensity()))
                .build();
    }

    private String deriveSentiment(Set<String> states) {
        boolean neg = states.stream().anyMatch(NEGATIVE_EMOTIONS::contains);
        boolean pos = states.stream().anyMatch(POSITIVE_EMOTIONS::contains);
        boolean calm = states.stream().anyMatch(NEUTRAL_CALM_EMOTIONS::contains);
        if (neg && pos) return "MIXED";
        if (neg) return "NEGATIVE";
        if (pos) return "POSITIVE";
        if (calm) return "NEUTRAL";
        return "NEUTRAL";
    }

    private String buildContextSummary(String primary, List<String> secondary, double intensity) {
        String intensityWord = intensity >= 0.7 ? "elevated" : intensity >= 0.4 ? "moderate" : "mild";
        StringBuilder sb = new StringBuilder("Conversation indicates ")
                .append(intensityWord).append(' ')
                .append(primary.toLowerCase()).append("-related emotional cues");
        if (!secondary.isEmpty()) {
            sb.append(", with traces of ")
              .append(String.join(" and ", secondary.stream().map(String::toLowerCase).toList()));
        }
        sb.append('.');
        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String normalizeSpeaker(String raw) {
        String upper = raw == null ? "" : raw.trim().toUpperCase().replace(' ', '_');
        if (upper.contains("CONSULTANT") || upper.contains("DOCTOR") || upper.contains("THERAPIST")) {
            return ConversationTurnDTO.HEALTH_CONSULTANT;
        }
        if (upper.contains("PATIENT")) {
            return ConversationTurnDTO.PATIENT;
        }
        return ConversationTurnDTO.PATIENT; // safest default — analysis stays patient-focused
    }

    private String escapeJson(String text) {
        return text.replace("\"", "\\\"").replace("\n", " ");
    }

    private String extractJsonBlock(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.strip();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }
}
