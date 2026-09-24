import React, { useState } from "react";

const EXAMPLE_PROMPTS = [
  "I'm feeling really stressed from work today 😞",
  "Aaj bahut khush hoon, dil karta hai nacho!",
  "Feeling heartbroken and want to cry",
  "Need something calm to help me focus",
  "Bohot gussa aa raha hai, kuch intense chahiye",
  "Relaxing Sunday vibes, feeling peaceful",
];

/**
 * Text input panel with example prompt chips.
 * Calls onSubmit(message) when the user submits.
 */
export default function TextInput({ onSubmit, isLoading }) {
  const [text, setText] = useState("");

  const handleSubmit = (e) => {
    e.preventDefault();
    const trimmed = text.trim();
    if (!trimmed || isLoading) return;
    onSubmit(trimmed);
  };

  const useExample = (prompt) => {
    setText(prompt);
  };

  return (
    <div className="flex flex-col gap-3">
      {/* Suggestion chips */}
      <div className="flex flex-wrap gap-2">
        {EXAMPLE_PROMPTS.map((p) => (
          <button
            key={p}
            type="button"
            onClick={() => useExample(p)}
            className="text-xs bg-gray-700 hover:bg-gray-600 text-gray-300 hover:text-white px-3 py-1.5 rounded-full transition-colors duration-150"
          >
            {p.length > 38 ? p.slice(0, 38) + "…" : p}
          </button>
        ))}
      </div>

      {/* Textarea + submit */}
      <form onSubmit={handleSubmit} className="flex flex-col gap-2">
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Tell me how you're feeling… (Hindi ya English mein)"
          rows={3}
          disabled={isLoading}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) handleSubmit(e);
          }}
          className="w-full bg-gray-700 border border-gray-600 focus:border-brand-500 focus:ring-1 focus:ring-brand-500 rounded-xl px-4 py-3 text-sm text-white placeholder-gray-400 resize-none outline-none transition-colors"
        />

        <button
          type="submit"
          disabled={isLoading || !text.trim()}
          className="btn-primary self-end flex items-center gap-2 text-sm"
        >
          {isLoading ? (
            <>
              <Spinner />
              Analyzing…
            </>
          ) : (
            <>
              <SendIcon />
              Get My Playlist
            </>
          )}
        </button>
      </form>
    </div>
  );
}

function Spinner() {
  return (
    <svg className="animate-spin w-4 h-4" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor"
        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
    </svg>
  );
}

function SendIcon() {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" className="w-4 h-4" viewBox="0 0 24 24"
      fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <line x1="22" y1="2" x2="11" y2="13" />
      <polygon points="22 2 15 22 11 13 2 9 22 2" />
    </svg>
  );
}
