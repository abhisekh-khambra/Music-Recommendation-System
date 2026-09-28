import React from "react";
import TrackCard from "./TrackCard";

const CUE_LABELS = [
  { key: "stressCuesDetected",      label: "Stress",      emoji: "😰" },
  { key: "anxietyCuesDetected",     label: "Anxiety",      emoji: "😟" },
  { key: "sadnessCuesDetected",     label: "Low mood",     emoji: "😢" },
  { key: "relaxationCuesDetected",  label: "Relaxation",   emoji: "🍃" },
];

const SENTIMENT_STYLES = {
  POSITIVE: "bg-green-500/20 text-green-300 border-green-500/30",
  NEGATIVE: "bg-orange-500/20 text-orange-300 border-orange-500/30",
  MIXED:    "bg-purple-500/20 text-purple-300 border-purple-500/30",
  NEUTRAL:  "bg-teal-500/20 text-teal-300 border-teal-500/30",
};

/**
 * Full result panel for the conversation-based Raga pipeline:
 *   transcript (speaker turns) → patient analysis → raga selection → top-10 songs
 */
export default function ConversationResult({ data }) {
  if (!data) return null;

  const { turns, analysis, ragaSelection, recommendations, conversationalMessage, warnings } = data;

  return (
    <div className="flex flex-col gap-5 animate-fadeIn">
      {warnings?.length > 0 && (
        <div className="bg-yellow-900/30 border border-yellow-700/40 rounded-xl px-4 py-3">
          <p className="text-xs text-yellow-400 font-semibold uppercase tracking-wide mb-1">Note</p>
          <ul className="text-xs text-yellow-200 list-disc list-inside space-y-0.5">
            {warnings.map((w, i) => <li key={i}>{w}</li>)}
          </ul>
        </div>
      )}

      {/* ── Transcript with speaker turns ─────────────────────── */}
      {turns?.length > 0 && (
        <div className="bg-gray-800/60 border border-gray-700 rounded-xl px-4 py-3">
          <p className="text-xs text-gray-400 mb-2 uppercase tracking-wide">Conversation Transcript</p>
          <div className="flex flex-col gap-2 max-h-64 overflow-y-auto pr-1">
            {turns.map((t, i) => {
              const isConsultant = t.speakerRole === "HEALTH_CONSULTANT";
              return (
                <div key={i} className={`flex ${isConsultant ? "justify-start" : "justify-end"}`}>
                  <div className={`max-w-[80%] rounded-2xl px-3 py-2 text-sm
                      ${isConsultant
                        ? "bg-gray-700 text-gray-200 rounded-tl-sm"
                        : "bg-brand-600 text-white rounded-tr-sm"}`}>
                    <p className="text-[10px] uppercase tracking-wide opacity-70 mb-0.5">
                      {isConsultant ? "Health Consultant" : "Patient"}
                    </p>
                    {t.text}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* ── Patient analysis (non-diagnostic) ─────────────────── */}
      {analysis && (
        <div className="bg-gray-800/60 border border-gray-700 rounded-xl px-4 py-3">
          <div className="flex items-center justify-between flex-wrap gap-2 mb-2">
            <p className="text-xs text-gray-400 uppercase tracking-wide">Patient Analysis</p>
            <span className={`emotion-badge border ${SENTIMENT_STYLES[analysis.sentiment] || SENTIMENT_STYLES.NEUTRAL}`}>
              {analysis.sentiment}
            </span>
          </div>

          <p className="text-sm text-gray-200 mb-2">{analysis.contextSummary}</p>

          <div className="flex flex-wrap gap-2 mb-2">
            {CUE_LABELS.filter((c) => analysis[c.key]).map((c) => (
              <span key={c.key} className="emotion-badge bg-gray-700/60 text-gray-300 border border-gray-600">
                {c.emoji} {c.label} cues detected
              </span>
            ))}
            {CUE_LABELS.every((c) => !analysis[c.key]) && (
              <span className="text-xs text-gray-500">No strong emotional cues flagged.</span>
            )}
          </div>

          <div className="text-xs text-gray-400 flex flex-wrap gap-x-4 gap-y-1">
            <span>Primary state: <span className="text-gray-300">{analysis.primaryEmotionalState}</span></span>
            <span>Intensity: <span className="text-gray-300">{Math.round((analysis.emotionalIntensity ?? 0) * 100)}%</span></span>
            <span>Language: <span className="text-gray-300">{analysis.detectedLanguage}</span></span>
          </div>

          <p className="text-[11px] text-gray-500 italic mt-2 border-t border-gray-700 pt-2">
            {analysis.disclaimer}
          </p>
        </div>
      )}

      {/* ── Raga selection ─────────────────────────────────────── */}
      {ragaSelection && (
        <div className="bg-brand-900/30 border border-brand-700/40 rounded-xl px-4 py-3">
          <p className="text-xs text-brand-400 font-semibold uppercase tracking-wide mb-1">
            Selected Raga
          </p>
          <p className="text-lg font-bold text-white">
            {ragaSelection.raga}
            <span className="ml-2 text-sm font-normal text-gray-400">({ragaSelection.rasa})</span>
          </p>
          <p className="text-sm text-gray-200 mt-1 leading-relaxed">{ragaSelection.reason}</p>
          {ragaSelection.alternateRagas?.length > 0 && (
            <p className="text-xs text-gray-400 mt-2">
              Also traditionally associated: {ragaSelection.alternateRagas.join(", ")}
            </p>
          )}
        </div>
      )}

      {/* ── LLM-free conversational summary ───────────────────── */}
      {conversationalMessage && (
        <div className="bg-gray-800/40 border border-gray-700 rounded-xl px-4 py-3">
          <p className="text-sm text-gray-200 leading-relaxed">{conversationalMessage}</p>
        </div>
      )}

      {/* ── Track list ─────────────────────────────────────────── */}
      {recommendations?.length > 0 && (
        <div className="flex flex-col gap-2">
          <p className="text-xs text-gray-400 uppercase tracking-wide font-medium">
            Top {recommendations.length} Raga-Based Songs
          </p>
          {recommendations.map((track, i) => (
            <TrackCard key={track.id} track={track} rank={i + 1} />
          ))}
        </div>
      )}
    </div>
  );
}
