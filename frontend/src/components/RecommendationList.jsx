import React from "react";
import TrackCard from "./TrackCard";

const EMOTION_EMOJIS = {
  JOY: "😊", SADNESS: "😢", ANGER: "😠", STRESS: "😰",
  RELAXATION: "😌", ANXIETY: "😟", EXCITEMENT: "🤩",
  LOVE: "❤️", NOSTALGIA: "🌅", MELANCHOLY: "🌧️", CALM: "🍃",
};

const AROUSAL_STYLES = {
  HIGH:    "bg-red-500/20 text-red-300 border-red-500/30",
  MEDIUM:  "bg-amber-500/20 text-amber-300 border-amber-500/30",
  LOW:     "bg-sky-500/20 text-sky-300 border-sky-500/30",
  UNKNOWN: "bg-gray-700/40 text-gray-400 border-gray-600",
};

/**
 * Displays the full recommendation result:
 *   1. Emotion summary panel (with a text/audio/both source indicator)
 *   2. LLM conversational message
 *   3. Top-10 Hindi-only track cards
 */
export default function RecommendationList({ data }) {
  if (!data) return null;

  const { emotion, conversationalMessage, recommendations, transcript, warnings } = data;
  const emotionKey = emotion?.primary_emotion?.toUpperCase();
  const emoji = EMOTION_EMOJIS[emotionKey] || "🎵";
  const audioProfile = emotion?.audio_emotion_profile;
  const emotionSource = audioProfile && audioProfile.arousalLevel !== "UNKNOWN" ? "TEXT + AUDIO" : "TEXT ONLY";

  return (
    <div className="flex flex-col gap-5 animate-fadeIn">
      {/* Non-fatal degradations (e.g. fewer than 10 eligible Hindi tracks) */}
      {warnings?.length > 0 && (
        <div className="bg-yellow-900/30 border border-yellow-700/40 rounded-xl px-4 py-3">
          <p className="text-xs text-yellow-400 font-semibold uppercase tracking-wide mb-1">Note</p>
          <ul className="text-xs text-yellow-200 list-disc list-inside space-y-0.5">
            {warnings.map((w, i) => <li key={i}>{w}</li>)}
          </ul>
        </div>
      )}

      {/* Transcript (if from voice) */}
      {transcript && (
        <div className="bg-gray-800/60 border border-gray-700 rounded-xl px-4 py-3">
          <p className="text-xs text-gray-400 mb-1 uppercase tracking-wide">You said</p>
          <p className="text-sm text-gray-200 italic">"{transcript}"</p>
        </div>
      )}

      {/* Emotion banner */}
      {emotion && (
        <div className="flex flex-wrap items-center gap-4 bg-gray-800/60 border border-gray-700 rounded-xl px-4 py-3">
          <span className="text-3xl">{emoji}</span>
          <div>
            <div className="flex items-center gap-2">
              <p className="text-xs text-gray-400 uppercase tracking-wide">Detected Emotion</p>
              <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-gray-700 text-gray-400 uppercase tracking-wide">
                {emotionSource}
              </span>
            </div>
            <p className="text-lg font-bold text-white">
              {emotion.primary_emotion}
              <span className="ml-2 text-sm font-normal text-gray-400">
                (intensity: {Math.round((emotion.intensity ?? 0) * 100)}%)
              </span>
            </p>
            {emotion.secondary_emotions?.length > 0 && (
              <p className="text-xs text-gray-400 mt-0.5">
                Also: {emotion.secondary_emotions.join(", ")}
              </p>
            )}
          </div>

          {/* Mood keywords */}
          {emotion.mood_keywords?.length > 0 && (
            <div className="flex flex-wrap gap-1 ml-auto">
              {emotion.mood_keywords.map((kw) => (
                <span key={kw} className="text-xs bg-gray-700 text-gray-300 px-2 py-0.5 rounded-full">
                  {kw}
                </span>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Audio-based (acoustic) emotion signal — voice input only */}
      {audioProfile && (
        <div className="bg-gray-800/60 border border-gray-700 rounded-xl px-4 py-3">
          <div className="flex items-center justify-between flex-wrap gap-2 mb-1">
            <p className="text-xs text-gray-400 uppercase tracking-wide">Audio-Based Vocal Arousal</p>
            <span className={`emotion-badge border ${AROUSAL_STYLES[audioProfile.arousalLevel] || AROUSAL_STYLES.UNKNOWN}`}>
              {audioProfile.arousalLevel}
            </span>
          </div>
          <p className="text-sm text-gray-300">{audioProfile.rationale}</p>
          {emotion.speech_emotion && (
            <p className="text-xs text-sky-300 mt-1">
              🗣 Voice sounds {emotion.speech_emotion.label} ({Math.round((emotion.speech_emotion.confidence ?? 0) * 100)}%)
              {emotion.text_emotion && emotion.text_emotion !== emotion.primary_emotion
                ? ` — words said ${emotion.text_emotion}` : ""}
            </p>
          )}
          {emotion.fusion_note && (
            <p className="text-xs text-gray-500 italic mt-1">{emotion.fusion_note}</p>
          )}
        </div>
      )}

      {/* LLM conversational message */}
      {conversationalMessage && (
        <div className="bg-brand-900/30 border border-brand-700/40 rounded-xl px-4 py-3">
          <p className="text-xs text-brand-400 mb-1 font-semibold uppercase tracking-wide">
            Your Personalised Playlist
          </p>
          <p className="text-sm text-gray-200 leading-relaxed">{conversationalMessage}</p>
        </div>
      )}

      {/* Track list */}
      {recommendations?.length > 0 && (
        <div className="flex flex-col gap-2">
          <p className="text-xs text-gray-400 uppercase tracking-wide font-medium">
            Top {recommendations.length} Hindi Songs for You • Click a card to play on YouTube
          </p>
          {recommendations.map((track, i) => (
            <TrackCard key={track.id} track={track} rank={i + 1} />
          ))}
        </div>
      )}
    </div>
  );
}
