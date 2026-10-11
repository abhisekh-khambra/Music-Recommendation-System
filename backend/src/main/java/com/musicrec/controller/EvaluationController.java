package com.musicrec.controller;

import com.musicrec.evaluation.AudioEvaluationReport;
import com.musicrec.evaluation.AudioEvaluationService;
import com.musicrec.evaluation.EvaluationReport;
import com.musicrec.evaluation.EvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * Runs the labelled-conversation evaluation (EvaluationService) and returns the report; the same
 * report is written as JSON + Markdown to app.evaluation.results-dir. Long-running (~10–20 s per
 * case, mostly LLM calls) and uses real Groq quota.
 *
 *   POST /api/v1/evaluation/run                    all cases
 *   POST /api/v1/evaluation/run?limit=3            first 3 cases
 *   POST /api/v1/evaluation/run?caseIds=C02,C15    selected cases
 */
@RestController
@RequestMapping("/api/v1/evaluation")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final AudioEvaluationService audioEvaluationService;

    @Value("${app.evaluation.audio-manifest:../evaluation/audio/manifest_synthetic.json}")
    private String defaultAudioManifest;

    @PostMapping("/run")
    public ResponseEntity<EvaluationReport> run(
            @RequestParam(value = "caseIds", required = false) List<String> caseIds,
            @RequestParam(value = "limit", required = false) Integer limit) throws IOException {
        return ResponseEntity.ok(evaluationService.run(caseIds, limit));
    }

    /**
     * Audio evaluation (speaker separation + voice/text/fused emotion) on labelled recordings.
     * Needs the speech-emotion service running.
     *
     *   POST /api/v1/evaluation/run-audio                                  synthetic test recordings
     *   POST /api/v1/evaluation/run-audio?manifest=../evaluation/audio/manifest_real.json
     */
    @PostMapping("/run-audio")
    public ResponseEntity<AudioEvaluationReport> runAudio(
            @RequestParam(value = "manifest", required = false) String manifest) throws IOException {
        return ResponseEntity.ok(audioEvaluationService.run(manifest == null || manifest.isBlank() ? defaultAudioManifest : manifest));
    }
}
