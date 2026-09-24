import React, { useState, useRef, useCallback } from "react";

const EMOTION_COLORS = {
  JOY: "text-yellow-400",
  SADNESS: "text-blue-400",
  ANGER: "text-red-400",
  STRESS: "text-orange-400",
  RELAXATION: "text-green-400",
  ANXIETY: "text-purple-400",
  EXCITEMENT: "text-pink-400",
  LOVE: "text-rose-400",
  NOSTALGIA: "text-amber-400",
  MELANCHOLY: "text-indigo-400",
  CALM: "text-teal-400",
};

/**
 * Audio recorder that uses the browser MediaRecorder API.
 * On stop → sends the WebM blob to the parent via onAudioReady.
 */
export default function VoiceInput({ onAudioReady, isLoading }) {
  const [recording, setRecording] = useState(false);
  const [duration, setDuration] = useState(0);
  const mediaRecorderRef = useRef(null);
  const chunksRef = useRef([]);
  const timerRef = useRef(null);

  const startRecording = useCallback(async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mr = new MediaRecorder(stream, { mimeType: "audio/webm;codecs=opus" });
      mediaRecorderRef.current = mr;
      chunksRef.current = [];

      mr.ondataavailable = (e) => {
        if (e.data.size > 0) chunksRef.current.push(e.data);
      };

      mr.onstop = () => {
        const blob = new Blob(chunksRef.current, { type: "audio/webm" });
        stream.getTracks().forEach((t) => t.stop());
        onAudioReady(blob);
      };

      mr.start(250); // collect chunks every 250 ms
      setRecording(true);
      setDuration(0);

      timerRef.current = setInterval(() => {
        setDuration((d) => d + 1);
      }, 1000);
    } catch (err) {
      alert("Microphone access denied. Please allow microphone permissions.");
      console.error(err);
    }
  }, [onAudioReady]);

  const stopRecording = useCallback(() => {
    if (mediaRecorderRef.current && recording) {
      mediaRecorderRef.current.stop();
      setRecording(false);
      clearInterval(timerRef.current);
    }
  }, [recording]);

  const formatDuration = (s) =>
    `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;

  return (
    <div className="flex flex-col items-center gap-4 py-2">
      {/* Waveform animation (shown while recording) */}
      {recording && (
        <div className="flex items-center gap-1 h-8">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="wave-bar" />
          ))}
          <span className="ml-3 text-sm text-gray-400 tabular-nums">
            {formatDuration(duration)}
          </span>
        </div>
      )}

      {/* Record / Stop button */}
      <button
        onClick={recording ? stopRecording : startRecording}
        disabled={isLoading}
        className={`relative flex items-center gap-2 px-6 py-3 rounded-full font-semibold text-sm transition-all duration-200 shadow-lg
          ${recording
            ? "bg-red-600 hover:bg-red-700 animate-pulse-slow"
            : "bg-brand-600 hover:bg-brand-700"
          } disabled:opacity-50 disabled:cursor-not-allowed`}
      >
        {/* Mic / stop icon */}
        {recording ? (
          <>
            <span className="inline-block w-3 h-3 bg-white rounded-sm" />
            Stop Recording
          </>
        ) : (
          <>
            <MicIcon />
            {isLoading ? "Processing…" : "Record Voice (Hindi / English)"}
          </>
        )}
      </button>

      <p className="text-xs text-gray-500">
        Speak naturally — tell us how you feel right now
      </p>
    </div>
  );
}

function MicIcon() {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" className="w-4 h-4" viewBox="0 0 24 24"
      fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 2a3 3 0 0 1 3 3v7a3 3 0 0 1-6 0V5a3 3 0 0 1 3-3z" />
      <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
      <line x1="12" y1="19" x2="12" y2="22" />
      <line x1="8"  y1="22" x2="16" y2="22" />
    </svg>
  );
}
