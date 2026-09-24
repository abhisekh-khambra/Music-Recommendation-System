import React, { useEffect, useRef } from "react";

const EMOTION_EMOJIS = {
  JOY: "😊", SADNESS: "😢", ANGER: "😠", STRESS: "😰",
  RELAXATION: "😌", ANXIETY: "😟", EXCITEMENT: "🤩",
  LOVE: "❤️", NOSTALGIA: "🌅", MELANCHOLY: "🌧️", CALM: "🍃",
};

/**
 * Scrollable chat history panel.
 * Each item represents one exchange: user message + assistant summary.
 */
export default function ChatInterface({ history }) {
  const bottomRef = useRef(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [history]);

  if (!history || history.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center h-40 text-gray-500 text-sm gap-2">
        <span className="text-3xl">🎵</span>
        <p>Your conversation will appear here</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4 max-h-80 overflow-y-auto pr-1">
      {history.map((item, i) => (
        <div key={i} className="flex flex-col gap-1">
          {/* User bubble */}
          <div className="flex justify-end">
            <div className="max-w-xs bg-brand-600 text-white text-sm px-4 py-2 rounded-2xl rounded-tr-sm shadow">
              {item.userMessage}
            </div>
          </div>

          {/* Assistant bubble */}
          {item.assistantResponse && (
            <div className="flex items-end gap-2">
              <div className="w-7 h-7 rounded-full bg-gray-700 flex items-center justify-center text-sm flex-shrink-0">
                {EMOTION_EMOJIS[item.detectedEmotion?.toUpperCase()] ?? "🤖"}
              </div>
              <div className="max-w-xs bg-gray-700 text-gray-200 text-sm px-4 py-2 rounded-2xl rounded-tl-sm shadow">
                <p className="text-xs text-gray-400 mb-0.5 font-medium">
                  {item.detectedEmotion} • {item.recommendedTrackIds?.length ?? 0} songs
                </p>
                {item.assistantResponse.slice(0, 120)}
                {item.assistantResponse.length > 120 && "…"}
              </div>
            </div>
          )}
        </div>
      ))}
      <div ref={bottomRef} />
    </div>
  );
}
