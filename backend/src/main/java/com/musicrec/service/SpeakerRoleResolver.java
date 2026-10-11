package com.musicrec.service;

import com.musicrec.audio.SpeakerDiarizationService;
import com.musicrec.dto.ConversationTurnDTO;
import com.musicrec.dto.TranscriptSegmentDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines audio-based speaker diarization (acoustic clustering of segments —
 * see com.musicrec.audio.SpeakerDiarizationService) with text-based
 * conversational-role labels (ConversationAnalysisService.labelRoles) to
 * produce the final per-segment speaker assignment.
 *
 * Why both are needed: acoustic clustering can reliably tell you "these N
 * segments are the same voice, those M are a different voice" from the audio
 * signal — but it has no idea which voice is semantically the Patient vs. the
 * Health Consultant. Text-based role cues answer that. So when diarization is
 * reliable, each acoustic cluster's role is decided by MAJORITY VOTE of the
 * per-segment text-role guesses across that cluster — i.e. the segmentation
 * itself is audio-driven, and text only breaks the "which one is which" tie.
 * This satisfies the requirement to not rely solely on LLM text segmentation:
 * if diarization is unreliable (e.g. a very short clip), this falls back to
 * the raw per-segment text-role labels alone, and says so in the warnings.
 */
@Service
@Slf4j
public class SpeakerRoleResolver {

    public record Resolution(List<ConversationTurnDTO> turns, String diarizationMethod, List<String> warnings) {}

    public Resolution resolve(SpeakerDiarizationService.DiarizationResult diarization, List<String> textRoles) {
        List<String> warnings = new ArrayList<>();
        List<SpeakerDiarizationService.DiarizedSegment> segments = diarization.segments();

        if (segments.isEmpty()) {
            warnings.add("No speech segments were available to assign speakers to.");
            return new Resolution(List.of(), "No audio segments available.", warnings);
        }

        Map<Integer, String> clusterRole;
        String method;

        if (diarization.reliable() && segments.size() == textRoles.size()) {
            clusterRole = assignRolesByMajorityVote(segments, textRoles);
            method = diarization.method() + " Each acoustic cluster's Patient/Health-Consultant label was "
                    + "then assigned by majority vote of the text-based role cues within that cluster.";
        } else {
            clusterRole = null; // signal: use raw per-segment text roles, no acoustic grouping trusted
            method = diarization.method() + " Falling back to per-segment text-based role labeling alone "
                    + "(acoustic diarization was not reliable enough to trust for grouping).";
            warnings.add("Audio-based speaker separation was not reliable for this recording — "
                    + "speaker labels fall back to text-based conversational-role cues per segment.");
        }

        List<ConversationTurnDTO> turns = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegmentDTO seg = segments.get(i).segment();
            String role = (clusterRole != null)
                    ? clusterRole.getOrDefault(segments.get(i).clusterId(), ConversationTurnDTO.PATIENT)
                    : (i < textRoles.size() ? textRoles.get(i) : ConversationTurnDTO.PATIENT);
            turns.add(new ConversationTurnDTO(seg.order(), role, seg.text(), seg.startMs(), seg.endMs()));
        }

        return new Resolution(turns, method, warnings);
    }

    private Map<Integer, String> assignRolesByMajorityVote(
            List<SpeakerDiarizationService.DiarizedSegment> segments, List<String> textRoles) {

        Map<Integer, Map<String, Integer>> votesByCluster = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            int cluster = segments.get(i).clusterId();
            String role = textRoles.get(i);
            votesByCluster.computeIfAbsent(cluster, k -> new HashMap<>())
                    .merge(role, 1, Integer::sum);
        }

        Map<Integer, String> clusterRole = new HashMap<>();
        votesByCluster.forEach((cluster, votes) -> {
            String winner = votes.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(ConversationTurnDTO.PATIENT);
            clusterRole.put(cluster, winner);
        });

        // If both clusters voted the same role (e.g. the text heuristic was ambiguous
        // throughout), force the second-place cluster to the opposite role so the two
        // acoustically-distinct voices still end up labeled differently — the acoustic
        // split is still meaningful even if the text tie-break degenerated.
        if (clusterRole.size() == 2) {
            var values = clusterRole.values().stream().distinct().toList();
            if (values.size() == 1) {
                Integer anyCluster = clusterRole.keySet().iterator().next();
                String other = ConversationTurnDTO.PATIENT.equals(values.get(0))
                        ? ConversationTurnDTO.HEALTH_CONSULTANT : ConversationTurnDTO.PATIENT;
                for (Integer cluster : clusterRole.keySet()) {
                    if (!cluster.equals(anyCluster)) {
                        clusterRole.put(cluster, other);
                    }
                }
            }
        }

        return clusterRole;
    }
}
