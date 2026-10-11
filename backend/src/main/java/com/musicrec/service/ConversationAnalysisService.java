package com.musicrec.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicrec.dto.ConversationAnalysisDTO;
import com.musicrec.dto.EmotionMetadata;
import com.musicrec.dto.EmotionalVariabilityDTO;
import com.musicrec.dto.PatientSegmentEmotionDTO;
import com.musicrec.dto.TranscriptSegmentDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Text-based conversational-role classification + non-diagnostic analysis for
 * the conversation pipeline.
 *
 * IMPORTANT — role LABELING here is text-based (conversational cues: who asks
 * vs. who describes their own feelings), but it is no longer the primary way
 * speakers are told apart. Real audio-based diarization now does that (see
 * com.musicrec.audio.SpeakerDiarizationService, which clusters Whisper's
 * time-aligned segments by acoustic pitch/energy). This service's per-segment
 * role guess is used by SpeakerRoleResolver only to decide which acoustic
 * cluster should be called "Patient" vs. "Health Consultant" — an acoustic
 * cluster has no idea of its own semantic role — via majority vote across a
 * cluster's segments, not as the primary segmentation mechanism anymore.
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

    private static final String ROLE_LABEL_TEMPLATE = """
            The following are time-ordered segments of a transcribed spoken conversation between a
            Health Consultant and a Patient. The segments themselves (their boundaries) were already
            determined from the audio — your ONLY job is to label which role spoke each one. Do not
            merge, split, reorder, or reword any segment.

            Labeling guidance:
            - The Health Consultant typically asks questions, offers guidance, or responds in a
              professional/supportive register.
            - The Patient typically describes their own feelings, symptoms, sleep, stress, or
              day-to-day experience in the first person.
            - If a segment's speaker is ambiguous, use the question/answer alternation pattern of the
              conversation to decide.

            Segments:
            %s

            Return ONLY this JSON — no markdown, no extra text:
            { "labels": [ {"index": <segment number>, "speaker": "HEALTH_CONSULTANT" | "PATIENT"} ] }
            """;

    public ConversationAnalysisService(
            @Qualifier("conversationChatClient") ChatClient conversationChatClient,
            ObjectMapper objectMapper) {
        this.conversationChatClient = conversationChatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Classifies each already-segmented transcript segment as HEALTH_CONSULTANT or PATIENT,
     * purely from conversational-role text cues. Returns one role string per input segment,
     * same order — never re-splits or reorders the segments themselves.
     */
    public List<String> labelRoles(List<TranscriptSegmentDTO> segments) {
        if (segments.isEmpty()) return List.of();

        try {
            StringBuilder numbered = new StringBuilder();
            for (TranscriptSegmentDTO seg : segments) {
                numbered.append(seg.order()).append(". \"").append(escapeJson(seg.text())).append("\"\n");
            }

            String prompt = ROLE_LABEL_TEMPLATE.formatted(numbered.toString());
            String rawJson = conversationChatClient.prompt().user(prompt).call().content();
            JsonNode root = objectMapper.readTree(extractJsonBlock(rawJson));

            String[] roles = new String[segments.size()];
            for (JsonNode node : root.path("labels")) {
                int index = node.path("index").asInt(-1);
                if (index >= 0 && index < roles.length) {
                    roles[index] = normalizeSpeaker(node.path("speaker").asText(""));
                }
            }
            // Any segment the LLM skipped defaults to PATIENT — the safer default for
            // downstream patient-focused analysis (better to over-include than silently drop).
            for (int i = 0; i < roles.length; i++) {
                if (roles[i] == null) roles[i] = PATIENT_DEFAULT;
            }
            return List.of(roles);

        } catch (Exception ex) {
            log.warn("Role labeling failed — defaulting every segment to PATIENT. Cause: {}", ex.getMessage());
            return segments.stream().map(s -> PATIENT_DEFAULT).toList();
        }
    }

    private static final String PATIENT_DEFAULT = com.musicrec.dto.ConversationTurnDTO.PATIENT;

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

    /**
     * Aggregates independently-analyzed per-segment emotions (from
     * EmotionAnalysisService.analyzeBatch) into a pure-code emotional-variability
     * profile — req. #3: don't force a whole conversation into one fixed emotion
     * when different segments show different states.
     */
    public EmotionalVariabilityDTO buildVariability(List<PatientSegmentEmotionDTO> segments) {
        if (segments.isEmpty()) {
            return EmotionalVariabilityDTO.builder()
                    .segments(List.of())
                    .distinctEmotionCount(0)
                    .transitionCount(0)
                    .intensityVariance(0.0)
                    .emotionDistribution(new java.util.LinkedHashMap<>())
                    .build();
        }

        Set<String> distinct = new java.util.LinkedHashSet<>();
        int transitions = 0;
        String previous = null;
        double meanIntensity = segments.stream().mapToDouble(PatientSegmentEmotionDTO::getIntensity).average().orElse(0);
        double variance = segments.stream().mapToDouble(s -> Math.pow(s.getIntensity() - meanIntensity, 2)).average().orElse(0);

        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> intensitySums = new java.util.HashMap<>();
        for (PatientSegmentEmotionDTO s : segments) {
            distinct.add(s.getPrimaryEmotion());
            if (previous != null && !previous.equals(s.getPrimaryEmotion())) transitions++;
            previous = s.getPrimaryEmotion();
            counts.merge(s.getPrimaryEmotion(), 1, Integer::sum);
            intensitySums.merge(s.getPrimaryEmotion(), s.getIntensity(), Double::sum);
        }

        // Most frequent first; ties go to the emotion felt more intensely overall, then first-seen
        // (counts is insertion-ordered and the sort is stable).
        List<String> ordered = counts.keySet().stream()
                .sorted(java.util.Comparator.<String>comparingInt(counts::get).reversed()
                        .thenComparing(java.util.Comparator.<String>comparingDouble(intensitySums::get).reversed()))
                .toList();

        return EmotionalVariabilityDTO.builder()
                .segments(segments)
                .distinctEmotionCount(distinct.size())
                .transitionCount(transitions)
                .intensityVariance(Math.round(variance * 1000) / 1000.0)
                .dominantEmotion(ordered.get(0))
                .emotionDistribution(percentages(ordered, counts, segments.size()))
                .build();
    }

    /**
     * Percent per emotion to one decimal, summing to exactly 100 (largest-remainder rounding in tenths
     * of a percent). Leftover tenths go to the largest remainders, ties to the more dominant emotion,
     * so the list stays ordered and the dominant emotion never shows a smaller share than another.
     */
    private java.util.Map<String, Double> percentages(List<String> ordered, java.util.Map<String, Integer> counts, int total) {
        int n = ordered.size();
        int[] tenths = new int[n];
        long[] remainder = new long[n];
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            long scaled = counts.get(ordered.get(i)) * 1000L;   // share in tenths of a percent, times total
            tenths[i] = (int) (scaled / total);
            remainder[i] = scaled % total;
            assigned += tenths[i];
        }
        List<Integer> byRemainder = new ArrayList<>();
        for (int i = 0; i < n; i++) byRemainder.add(i);
        byRemainder.sort((a, b) -> remainder[a] != remainder[b] ? Long.compare(remainder[b], remainder[a]) : Integer.compare(a, b));
        for (int k = 0; k < 1000 - assigned; k++) tenths[byRemainder.get(k)]++;

        java.util.Map<String, Double> distribution = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) distribution.put(ordered.get(i), tenths[i] / 10.0);
        return distribution;
    }

    /**
     * Copy of the overall Patient emotion whose secondary emotions lead with the other emotions in
     * the response-level distribution (most frequent first), so retrieval reflects the whole mix of
     * emotions in the conversation rather than only the single overall label.
     */
    public EmotionMetadata withDistributionEmotions(EmotionMetadata overall, EmotionalVariabilityDTO variability) {
        EmotionMetadata copy = objectMapper.convertValue(overall, EmotionMetadata.class);
        Set<String> secondary = new java.util.LinkedHashSet<>();
        if (variability.getEmotionDistribution() != null) secondary.addAll(variability.getEmotionDistribution().keySet());
        if (overall.getSecondaryEmotions() != null) secondary.addAll(overall.getSecondaryEmotions());
        secondary.remove(overall.getPrimaryEmotionSafe());
        copy.setSecondaryEmotions(new java.util.ArrayList<>(secondary));
        return copy;
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
            return com.musicrec.dto.ConversationTurnDTO.HEALTH_CONSULTANT;
        }
        if (upper.contains("PATIENT")) {
            return com.musicrec.dto.ConversationTurnDTO.PATIENT;
        }
        return com.musicrec.dto.ConversationTurnDTO.PATIENT; // safest default — analysis stays patient-focused
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
