import React, { useEffect, useState } from "react";
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

const AROUSAL_STYLES = {
  HIGH:    "bg-red-500/20 text-red-300 border-red-500/30",
  MEDIUM:  "bg-amber-500/20 text-amber-300 border-amber-500/30",
  LOW:     "bg-sky-500/20 text-sky-300 border-sky-500/30",
  UNKNOWN: "bg-gray-700/40 text-gray-400 border-gray-600",
};

// Fixed hex colors (not Tailwind classes) because the timeline bar sets them as inline
// background-color on proportionally-sized flex segments.
const TIMELINE_COLORS = {
  JOY: "#eab308", SADNESS: "#3b82f6", ANGER: "#ef4444", STRESS: "#f97316",
  RELAXATION: "#22c55e", ANXIETY: "#a855f7", EXCITEMENT: "#ec4899",
  LOVE: "#f43f5e", NOSTALGIA: "#f59e0b", MELANCHOLY: "#6366f1", CALM: "#14b8a6",
  FRUSTRATION: "#dc2626",
};
const DEFAULT_TIMELINE_COLOR = "#6b7280";

function formatMs(ms) {
  if (!ms && ms !== 0) return "";
  const totalSeconds = Math.round(ms / 1000);
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

/** Horizontal timeline strip: one colored segment per patient utterance, sized by duration. */
function EmotionTimeline({ segments }) {
  const totalMs = Math.max(1, ...segments.map((s) => s.endMs || 0));
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex w-full h-5 rounded-lg overflow-hidden border border-gray-700">
        {segments.map((s, i) => {
          const widthPct = Math.max(2, ((s.endMs - s.startMs) / totalMs) * 100);
          return (
            <div
              key={i}
              title={`${formatMs(s.startMs)}–${formatMs(s.endMs)} · ${s.primaryEmotion} (${Math.round((s.intensity ?? 0) * 100)}%)`}
              style={{ width: `${widthPct}%`, backgroundColor: TIMELINE_COLORS[s.primaryEmotion] || DEFAULT_TIMELINE_COLOR }}
              className="h-full first:rounded-l-lg last:rounded-r-lg"
            />
          );
        })}
      </div>
      <div className="flex flex-wrap gap-2.5 text-[10px] text-gray-500">
        {[...new Set(segments.map((s) => s.primaryEmotion))].map((emotion) => (
          <span key={emotion} className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-full inline-block" style={{ backgroundColor: TIMELINE_COLORS[emotion] || DEFAULT_TIMELINE_COLOR }} />
            {emotion}
          </span>
        ))}
      </div>
    </div>
  );
}

/**
 * Full result panel for the conversation-based Raga pipeline:
 *   transcript (speaker turns, audio-diarized, editable) → audio arousal →
 *   emotional-variability timeline → patient analysis → raga selection →
 *   Hindi-only top-10 songs, with regenerate.
 */
export default function ConversationResult({ data, onCorrectRoles }) {
  const [editedRoles, setEditedRoles] = useState({});
  const [editingRoles, setEditingRoles] = useState(false);

  useEffect(() => {
    setEditedRoles({});
    setEditingRoles(false);
  }, [data]);

  if (!data) return null;

  const {
    turns, analysis, ragaSelection, recommendations, conversationalMessage, warnings,
    audioEmotionProfile, emotionalVariability, diarizationMethod, speakerAssignmentUncertain,
    emotionAllocation,
  } = data;

  const roleFor = (t) => editedRoles[t.order] ?? t.speakerRole;
  const toggleRole = (order, currentRole) => {
    const next = currentRole === "HEALTH_CONSULTANT" ? "PATIENT" : "HEALTH_CONSULTANT";
    setEditedRoles((prev) => ({ ...prev, [order]: next }));
  };
  const hasPendingCorrections = Object.keys(editedRoles).length > 0;

  const applyCorrections = () => {
    if (!onCorrectRoles) return;
    const correctedTurns = turns.map((t) => ({
      order: t.order,
      speakerRole: roleFor(t),
      text: t.text,
      startMs: t.startMs,
      endMs: t.endMs,
    }));
    onCorrectRoles(correctedTurns);
  };

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

      {/* ── Speaker-assignment uncertainty banner (req. #3) ───── */}
      {speakerAssignmentUncertain && !editingRoles && (
        <div className="bg-orange-900/30 border border-orange-700/40 rounded-xl px-4 py-3 flex items-center justify-between gap-3 flex-wrap">
          <p className="text-sm text-orange-200">
            ⚠️ Automatic speaker separation wasn't confident for this recording — please review who's labeled Patient vs. Health Consultant below.
          </p>
          <button
            onClick={() => setEditingRoles(true)}
            className="text-xs px-3 py-1.5 rounded-lg bg-orange-700 hover:bg-orange-600 text-white transition-colors shrink-0"
          >
            Review & Correct
          </button>
        </div>
      )}

      {/* ── Transcript with speaker turns (editable) ──────────── */}
      {turns?.length > 0 && (
        <div className="bg-gray-800/60 border border-gray-700 rounded-xl px-4 py-3">
          <div className="flex items-center justify-between gap-2 mb-2">
            <p className="text-xs text-gray-400 uppercase tracking-wide">Conversation Transcript</p>
            {onCorrectRoles && !editingRoles && (
              <button
                onClick={() => setEditingRoles(true)}
                className="text-[11px] px-2 py-1 rounded-md bg-gray-700 hover:bg-gray-600 text-gray-300 transition-colors"
              >
                ✏️ Correct speaker labels
              </button>
            )}
          </div>
          <div className="flex flex-col gap-2 max-h-64 overflow-y-auto pr-1">
            {turns.map((t, i) => {
              const role = roleFor(t);
              const isConsultant = role === "HEALTH_CONSULTANT";
              const wasEdited = editedRoles[t.order] !== undefined;
              return (
                <div key={i} className={`flex ${isConsultant ? "justify-start" : "justify-end"}`}>
                  <div className={`max-w-[80%] rounded-2xl px-3 py-2 text-sm
                      ${isConsultant
                        ? "bg-gray-700 text-gray-200 rounded-tl-sm"
                        : "bg-brand-600 text-white rounded-tr-sm"}
                      ${wasEdited ? "ring-2 ring-orange-400/60" : ""}`}>
                    <p className="text-[10px] uppercase tracking-wide opacity-70 mb-0.5 flex justify-between items-center gap-2">
                      {editingRoles ? (
                        <button
                          onClick={() => toggleRole(t.order, role)}
                          className="underline hover:opacity-100 opacity-90"
                          title="Click to swap speaker"
                        >
                          {isConsultant ? "Health Consultant" : "Patient"} ⇄
                        </button>
                      ) : (
                        <span>{isConsultant ? "Health Consultant" : "Patient"}</span>
                      )}
                      {(t.startMs || t.endMs) ? <span className="opacity-60">{formatMs(t.startMs)}–{formatMs(t.endMs)}</span> : null}
                    </p>
                    {t.text}
                  </div>
                </div>
              );
            })}
          </div>

          {editingRoles && (
            <div className="flex items-center justify-end gap-2 mt-3 pt-3 border-t border-gray-700">
              <button
                onClick={() => { setEditedRoles({}); setEditingRoles(false); }}
                className="text-xs px-3 py-1.5 rounded-lg text-gray-400 hover:text-gray-200 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={applyCorrections}
                disabled={!hasPendingCorrections}
                className="text-xs px-3 py-1.5 rounded-lg bg-brand-600 hover:bg-brand-500 disabled:opacity-40 disabled:cursor-not-allowed text-white transition-colors"
              >
                Apply corrections & recompute
              </button>
            </div>
          )}

          {diarizationMethod && (
            <p className="text-[11px] text-gray-500 italic mt-2 border-t border-gray-700 pt-2">
              How speakers were separated: {diarizationMethod}
            </p>
          )}
        </div>
      )}

      {/* ── Audio-based (acoustic) emotion signal ─────────────── */}
      {audioEmotionProfile && (
        <div className="bg-gray-800/60 border border-gray-700 rounded-xl px-4 py-3">
          <div className="flex items-center justify-between flex-wrap gap-2 mb-2">
            <p className="text-xs text-gray-400 uppercase tracking-wide">Audio-Based Vocal Arousal</p>
            <span className={`emotion-badge border ${AROUSAL_STYLES[audioEmotionProfile.arousalLevel] || AROUSAL_STYLES.UNKNOWN}`}>
              {audioEmotionProfile.arousalLevel}
            </span>
          </div>
          <p className="text-sm text-gray-200 mb-2">{audioEmotionProfile.rationale}</p>
          <div className="text-xs text-gray-400 flex flex-wrap gap-x-4 gap-y-1">
            <span>Confidence: <span className="text-gray-300">{Math.round((audioEmotionProfile.confidence ?? 0) * 100)}%</span></span>
            <span>Mean pitch: <span className="text-gray-300">{Math.round(audioEmotionProfile.meanPitchHz ?? 0)}Hz</span></span>
            <span>Voiced: <span className="text-gray-300">{Math.round(audioEmotionProfile.voicedRatioPct ?? 0)}%</span></span>
          </div>
          <p className="text-[11px] text-gray-500 italic mt-2 border-t border-gray-700 pt-2">
            {audioEmotionProfile.limitations}
          </p>
        </div>
      )}

      {/* ── Per-segment emotional variability + timeline ──────── */}
      {emotionalVariability?.segments?.length > 0 && (
        <div className="bg-gray-800/60 border border-gray-700 rounded-xl px-4 py-3">
          <div className="flex items-center justify-between flex-wrap gap-2 mb-3">
            <p className="text-xs text-gray-400 uppercase tracking-wide">Emotional Variability Across the Conversation</p>
            <span className="text-xs text-gray-400">
              {emotionalVariability.distinctEmotionCount} states · {emotionalVariability.transitionCount} shifts
            </span>
          </div>

          <EmotionTimeline segments={emotionalVariability.segments} />

          {emotionalVariability.dominantEmotion && (
            <div className="mt-3">
              <p className="text-xs text-gray-400 mb-1.5">
                Dominant emotion: <span className="text-gray-200 font-medium">{emotionalVariability.dominantEmotion}</span>
                <span className="text-gray-500"> · share of the patient's {emotionalVariability.segments.length} analysed responses</span>
              </p>
              <div className="flex flex-col gap-1">
                {Object.entries(emotionalVariability.emotionDistribution || {}).map(([emotion, pct]) => (
                  <div key={emotion} className="flex items-center gap-2 text-xs">
                    <span className="w-24 shrink-0 text-gray-300">{emotion}</span>
                    <div className="flex-1 h-2 bg-gray-900/60 rounded-full overflow-hidden">
                      <div className="h-full bg-purple-500/70 rounded-full" style={{ width: `${pct}%` }} />
                    </div>
                    <span className="w-12 text-right text-gray-400">{pct}%</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div className="flex flex-col gap-1.5 max-h-56 overflow-y-auto pr-1 mt-3">
            {emotionalVariability.segments.map((s, i) => (
              <div key={i} className="flex items-center justify-between gap-2 bg-gray-900/40 rounded-lg px-2.5 py-1.5 text-xs">
                <span className="text-gray-500 shrink-0">{formatMs(s.startMs)}–{formatMs(s.endMs)}</span>
                <span className="text-gray-300 truncate flex-1 px-2">{s.text}</span>
                <span className="emotion-badge bg-gray-700/60 text-gray-300 border border-gray-600 shrink-0"
                      title={s.fusionNote || ""}>
                  {s.primaryEmotion} {Math.round((s.intensity ?? 0) * 100)}%
                </span>
                {s.voiceEmotion && (
                  <span className="emotion-badge border shrink-0 bg-sky-900/30 text-sky-200 border-sky-700/50"
                        title={`Words: ${s.textEmotion} · Voice: ${s.voiceEmotion}`}>
                    🗣 {s.voiceEmotion} {Math.round((s.voiceEmotionConfidence ?? 0) * 100)}%
                  </span>
                )}
                {s.audioArousalLevel && (
                  <span className={`emotion-badge border shrink-0 ${AROUSAL_STYLES[s.audioArousalLevel] || AROUSAL_STYLES.UNKNOWN}`}>
                    🎙 {s.audioArousalLevel}
                  </span>
                )}
              </div>
            ))}
          </div>
          {data.speechEmotionMethod && (
            <p className="text-[11px] text-gray-400 mt-2">{data.speechEmotionMethod}</p>
          )}
          <p className="text-[11px] text-gray-500 italic mt-2 border-t border-gray-700 pt-2">
            Per-segment predictions, not confirmed states — see overall Patient Analysis below for the aggregated profile.
          </p>
        </div>
      )}

      {/* ── Patient analysis (non-diagnostic) ─────────────────── */}
      {analysis && (
        <div className="bg-gray-800/60 border border-gray-700 rounded-xl px-4 py-3">
          <div className="flex items-center justify-between flex-wrap gap-2 mb-2">
            <div className="flex items-center gap-2">
              <p className="text-xs text-gray-400 uppercase tracking-wide">Patient Analysis</p>
              <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-gray-700 text-gray-400 uppercase tracking-wide">
                {audioEmotionProfile && audioEmotionProfile.arousalLevel !== "UNKNOWN" ? "TEXT + AUDIO" : "TEXT ONLY"}
              </span>
            </div>
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
            Top {recommendations.length} Raga-Based Hindi Songs
          </p>
          {emotionAllocation?.length > 0 && (
            <div className="bg-gray-800/60 border border-gray-700 rounded-xl px-4 py-3">
              <p className="text-xs text-gray-400 mb-2">
                Playlist split by the share of each emotion in the patient's responses
              </p>
              <div className="flex flex-wrap gap-2">
                {emotionAllocation.map((s) => (
                  <span key={s.emotion}
                        className={`emotion-badge border ${s.filledSlots < s.targetSlots
                          ? "bg-yellow-900/30 text-yellow-200 border-yellow-700/40"
                          : "bg-purple-900/40 text-purple-200 border-purple-700/50"}`}>
                    {s.emotion} {Math.round(s.sharePercent)}% → {s.filledSlots}/{s.targetSlots} songs
                    {s.raga ? ` · Raga ${s.raga}` : " · no raga"}
                  </span>
                ))}
              </div>
            </div>
          )}
          {recommendations.map((track, i) => (
            <TrackCard key={track.id} track={track} rank={i + 1} />
          ))}
        </div>
      )}
    </div>
  );
}
