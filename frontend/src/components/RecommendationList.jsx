import React from "react";
import TrackCard from "./TrackCard";

const EMOTION_EMOJIS = {
  JOY: "😊", SADNESS: "😢", ANGER: "😠", STRESS: "😰",
  RELAXATION: "😌", ANXIETY: "😟", EXCITEMENT: "🤩",
  LOVE: "❤️", NOSTALGIA: "🌅", MELANCHOLY: "🌧️", CALM: "🍃",
};

/**
 * Displays the full recommendation result:
 *   1. Emotion summary panel
 *   2. LLM conversational message
 *   3. Top-10 track cards
 */
export default function RecommendationList({ data }) {
  if (!data) return null;

  const { emotion, conversationalMessage, recommendations, transcript } = data;
  const emotionKey = emotion?.primaryEmotion?.toUpperCase();
  const emoji = EMOTION_EMOJIS[emotionKey] || "🎵";

  return (
    <div className="flex flex-col gap-5 animate-fadeIn">
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
            <p className="text-xs text-gray-400 uppercase tracking-wide">Detected Emotion</p>
            <p className="text-lg font-bold text-white">
              {emotion.primaryEmotion}
              <span className="ml-2 text-sm font-normal text-gray-400">
                (intensity: {Math.round((emotion.intensity ?? 0) * 100)}%)
              </span>
            </p>
            {emotion.secondaryEmotions?.length > 0 && (
              <p className="text-xs text-gray-400 mt-0.5">
                Also: {emotion.secondaryEmotions.join(", ")}
              </p>
            )}
          </div>

          {/* Mood keywords */}
          {emotion.moodKeywords?.length > 0 && (
            <div className="flex flex-wrap gap-1 ml-auto">
              {emotion.moodKeywords.map((kw) => (
                <span key={kw} className="text-xs bg-gray-700 text-gray-300 px-2 py-0.5 rounded-full">
                  {kw}
                </span>
              ))}
            </div>
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
            Top {recommendations.length} Songs for You • Click a card to play on YouTube
          </p>
          {recommendations.map((track, i) => (
            <TrackCard key={track.id} track={track} rank={i + 1} />
          ))}
        </div>
      )}
    </div>
  );
}
