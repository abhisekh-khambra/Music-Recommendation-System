package com.musicrec.evaluation;

import com.musicrec.evaluation.EvaluationReport.CaseResult;

import java.util.Map;
import java.util.stream.Collectors;

/** Renders an EvaluationReport as Markdown tables, ready to paste into a paper draft. */
final class EvaluationMarkdown {

    private EvaluationMarkdown() {}

    static String render(EvaluationReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Evaluation report: ").append(r.dataset()).append("\n\n");
        sb.append("Run at ").append(r.runAt()).append(" · LLM `").append(r.llmModel()).append("` · K = ").append(r.k())
          .append(" · ").append(r.casesCompleted()).append(" of ").append(r.casesTotal()).append(" cases completed");
        if (r.casesFailed() > 0) sb.append(" (").append(r.casesFailed()).append(" failed)");
        if (r.casesWithLlmFallback() > 0) {
            sb.append("\n\n> **").append(r.casesWithLlmFallback())
              .append(" case(s) used an LLM fallback — see Notes; those results do not reflect the LLM pipeline.**");
        }
        sb.append("\n\n");

        sb.append("## Summary\n\n| Level | Metric | Value |\n|---|---|---|\n");
        row(sb, "Emotion", "Per-response accuracy (" + r.emotion().segmentsCorrect() + "/" + r.emotion().segmentsTotal() + ")",
                pct(r.emotion().segmentAccuracy()));
        row(sb, "Emotion", "Dominant-emotion accuracy", pct(r.emotion().dominantAccuracy()));
        row(sb, "Emotion", "Distribution similarity (1 − TVD)", num(r.emotion().meanDistributionSimilarity()));
        row(sb, "Raga", "Top-1 accuracy", pct(r.raga().top1Accuracy()));
        row(sb, "Raga", "Top-3 accuracy", pct(r.raga().top3Accuracy()));
        row(sb, "Raga", "Top-1 accuracy, reachable cases only (" + r.raga().reachableCases() + ")",
                pct(r.raga().top1AccuracyReachable()));
        row(sb, "Raga", "Catalogue agreement with detected emotions", pct(r.raga().catalogueAgreementRate()));
        row(sb, "Songs", "Precision@" + r.k(), num(r.songs().meanPrecisionAtK()));
        row(sb, "Songs", "Recall@" + r.k(), num(r.songs().meanRecallAtK()));
        row(sb, "Songs", "NDCG@" + r.k(), num(r.songs().meanNdcgAtK()));
        row(sb, "Songs", "Emotion-share fidelity of the playlist", num(r.songs().meanEmotionShareFidelity()));
        row(sb, "Songs", "Song emotion accuracy (primary emotion expected)", pct(r.songs().meanSongEmotionAccuracy()));
        row(sb, "Songs", "Song raga accuracy (" + pct(r.songs().meanRagaTaggedShare()) + " of songs raga-tagged)",
                pct(r.songs().meanSongRagaAccuracy()));
        row(sb, "Songs", "Genre accuracy (" + r.songs().genreLabelledCases() + " labelled cases)",
                r.songs().meanGenreAccuracy() == null ? "n/a — no genre labels" : pct(r.songs().meanGenreAccuracy()));
        row(sb, "Overall", "End-to-end accuracy (dominant emotion and Top-1 raga both correct)", pct(r.endToEndAccuracy()));
        sb.append("\n");

        sb.append("## By expected dominant emotion\n\n| Emotion | Cases | Dominant acc. | Raga Top-1 | NDCG@").append(r.k())
          .append(" |\n|---|---|---|---|---|\n");
        r.byExpectedDominantEmotion().forEach((e, b) -> sb.append("| ").append(e).append(" | ").append(b.cases())
                .append(" | ").append(pct(b.dominantAccuracy())).append(" | ").append(pct(b.ragaTop1Accuracy()))
                .append(" | ").append(num(b.meanNdcgAtK())).append(" |\n"));
        sb.append("\n");

        sb.append("## Cases\n\n| Case | Expected → detected dominant | Per-response | Raga (acceptable?) | P@").append(r.k())
          .append(" | NDCG@").append(r.k()).append(" | Share fidelity | Notes |\n|---|---|---|---|---|---|---|---|\n");
        for (CaseResult c : r.cases()) {
            if (c.error() != null) {
                sb.append("| ").append(c.id()).append(" | — | — | — | — | — | — | failed: ").append(escape(c.error())).append(" |\n");
                continue;
            }
            String notes = (c.keywordFallbackSegments() > 0 ? c.keywordFallbackSegments() + " response(s) via keyword fallback; " : "")
                    + (c.llmSongSelectionUsed() ? "" : "song selection fell back to rank order; ")
                    + (c.ragaReachable() ? "" : "no acceptable raga has Hindi tracks; ");
            sb.append("| ").append(c.id()).append(" ").append(escape(c.title()))
              .append(" | ").append(c.expectedDominant()).append(" → ").append(c.detectedDominant())
              .append(c.dominantCorrect() ? " ✓" : " ✗")
              .append(" | ").append(c.segmentsCorrect()).append("/").append(c.segmentsTotal())
              .append(" | ").append(c.selectedRaga()).append(c.ragaTop1Hit() ? " ✓" : (c.ragaTop3Hit() ? " (top-3)" : " ✗"))
              .append(" | ").append(num(c.precisionAtK())).append(" | ").append(num(c.ndcgAtK()))
              .append(" | ").append(num(c.emotionShareFidelity()))
              .append(" | ").append(notes.isEmpty() ? "" : notes.substring(0, notes.length() - 2)).append(" |\n");
        }
        sb.append("\n");

        sb.append("## Distributions per case\n\n| Case | Expected | Detected | Playlist composition |\n|---|---|---|---|\n");
        for (CaseResult c : r.cases()) {
            if (c.error() != null) continue;
            sb.append("| ").append(c.id()).append(" | ").append(dist(c.expectedDistribution()))
              .append(" | ").append(dist(c.detectedDistribution()))
              .append(" | ").append(dist(c.playlistComposition())).append(" |\n");
        }
        sb.append("\n");

        sb.append("## Notes on ground truth and limits\n\n");
        r.notes().forEach(n -> sb.append("- ").append(n).append("\n"));
        return sb.toString();
    }

    private static void row(StringBuilder sb, String level, String metric, String value) {
        sb.append("| ").append(level).append(" | ").append(metric).append(" | ").append(value).append(" |\n");
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }

    private static String num(double v) {
        return String.format("%.3f", v);
    }

    private static String dist(Map<String, Double> d) {
        return d.entrySet().stream().map(e -> e.getKey() + " " + Math.round(e.getValue()) + "%")
                .collect(Collectors.joining(", "));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
