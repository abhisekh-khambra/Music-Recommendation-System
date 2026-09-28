import React, { useState, useRef, useCallback } from "react";

/**
 * Input panel for the conversation-based Raga pipeline.
 * Upload a recorded conversation audio file — recording is handled by the
 * existing Voice Input tab, so this stays upload-only.
 * A single audio File is handed to onAudioReady.
 */
export default function ConversationInput({ onAudioReady, isLoading }) {
  const [fileName, setFileName] = useState(null);
  const fileInputRef = useRef(null);

  const handleFileSelect = useCallback((e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setFileName(file.name);
    onAudioReady(file);
    e.target.value = ""; // allow re-selecting the same file later
  }, [onAudioReady]);

  return (
    <div className="flex flex-col gap-4 py-1">
      <div>
        <p className="text-sm font-medium text-gray-200">Upload a conversation recording</p>
        <p className="text-xs text-gray-500 mt-0.5">
          A single audio file with two speakers — a Health Consultant and a Patient. WAV, MP3, M4A, or WebM, up to 25MB.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          disabled={isLoading}
          className="btn-primary text-sm flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <UploadIcon />
          {isLoading ? "Processing…" : "Upload Conversation Audio"}
        </button>
        <input
          ref={fileInputRef}
          type="file"
          accept="audio/*,video/webm"
          onChange={handleFileSelect}
          className="hidden"
        />
      </div>

      {fileName && !isLoading && (
        <p className="text-xs text-gray-400">
          Selected: <span className="text-gray-300">{fileName}</span>
        </p>
      )}

      <p className="text-xs text-gray-500">
        The system distinguishes speakers by conversational role (who asks vs. who describes their
        own feelings) rather than by voice — see "How It Works" below.
      </p>
    </div>
  );
}

function UploadIcon() {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" className="w-4 h-4" viewBox="0 0 24 24"
      fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <polyline points="17 8 12 3 7 8" />
      <line x1="12" y1="3" x2="12" y2="15" />
    </svg>
  );
}
