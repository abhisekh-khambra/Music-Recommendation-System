import React, { useState, useRef, useEffect } from "react";

const EMOTION_STYLES = {
  JOY:        { bg: "bg-yellow-500/20", text: "text-yellow-300", border: "border-yellow-500/30", emoji: "😊" },
  SADNESS:    { bg: "bg-blue-500/20",   text: "text-blue-300",   border: "border-blue-500/30",   emoji: "😢" },
  ANGER:      { bg: "bg-red-500/20",    text: "text-red-300",    border: "border-red-500/30",    emoji: "😠" },
  STRESS:     { bg: "bg-orange-500/20", text: "text-orange-300", border: "border-orange-500/30", emoji: "😰" },
  RELAXATION: { bg: "bg-green-500/20",  text: "text-green-300",  border: "border-green-500/30",  emoji: "😌" },
  ANXIETY:    { bg: "bg-purple-500/20", text: "text-purple-300", border: "border-purple-500/30", emoji: "😟" },
  EXCITEMENT: { bg: "bg-pink-500/20",   text: "text-pink-300",   border: "border-pink-500/30",   emoji: "🤩" },
  LOVE:       { bg: "bg-rose-500/20",   text: "text-rose-300",   border: "border-rose-500/30",   emoji: "❤️"  },
  NOSTALGIA:  { bg: "bg-amber-500/20",  text: "text-amber-300",  border: "border-amber-500/30",  emoji: "🌅" },
  MELANCHOLY: { bg: "bg-indigo-500/20", text: "text-indigo-300", border: "border-indigo-500/30", emoji: "🌧️" },
  CALM:       { bg: "bg-teal-500/20",   text: "text-teal-300",   border: "border-teal-500/30",   emoji: "🍃" },
};
const DEFAULT_STYLE = { bg: "bg-gray-700/50", text: "text-gray-300", border: "border-gray-600", emoji: "🎵" };

function formatDuration(ms) {
  if (!ms) return "--:--";
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

function formatTime(sec) {
  if (!sec || isNaN(sec)) return "0:00";
  return `${Math.floor(sec / 60)}:${String(Math.floor(sec % 60)).padStart(2, "0")}`;
}

// status: idle | loading | playing | paused | error
export default function TrackCard({ track, rank }) {
  const [expanded, setExpanded]       = useState(false);
  const [status, setStatus]           = useState("idle");
  const [previewUrl, setPreviewUrl]   = useState(null);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration]       = useState(30);
  const audioRef = useRef(null);

  const emotionKey = track.primaryEmotion?.toUpperCase();
  const style = EMOTION_STYLES[emotionKey] || DEFAULT_STYLE;

  // Pause & reset on unmount
  useEffect(() => () => audioRef.current?.pause(), []);

  // ── Fetch 30-second preview from iTunes (free, no API key, CORS-safe) ──
  const fetchPreview = async () => {
    setStatus("loading");
    try {
      const q   = encodeURIComponent(`${track.title} ${track.artist}`);
      const res = await fetch(
        `https://itunes.apple.com/search?term=${q}&media=music&entity=song&limit=1&country=US`
      );
      const json = await res.json();
      const url  = json.results?.[0]?.previewUrl;
      if (!url) throw new Error("no preview");
      setPreviewUrl(url);          // audio element picks it up via src
    } catch {
      setStatus("error");
    }
  };

  // ── Play / Pause toggle ──────────────────────────────────────────
  const handlePlayPause = (e) => {
    e.stopPropagation();
    if (status === "idle" || status === "error") {
      fetchPreview();
    } else if (status === "playing") {
      audioRef.current?.pause();
      setStatus("paused");
    } else if (status === "paused") {
      audioRef.current?.play();
      setStatus("playing");
    }
  };

  // ── Seek bar click ───────────────────────────────────────────────
  const handleSeek = (e) => {
    e.stopPropagation();
    if (!audioRef.current || !duration) return;
    const rect = e.currentTarget.getBoundingClientRect();
    audioRef.current.currentTime = ((e.clientX - rect.left) / rect.width) * duration;
  };

  const progressPct  = duration ? (currentTime / duration) * 100 : 0;
  const showPlayer   = ["loading", "playing", "paused"].includes(status);

  return (
    <div
      className={`card flex flex-col gap-0 cursor-pointer select-none ${style.border} border`}
      onClick={() => setExpanded(!expanded)}
    >
      {/* Hidden HTML5 audio – plays the iTunes 30s preview */}
      {previewUrl && (
        <audio
          ref={audioRef}
          src={previewUrl}
          onCanPlay={() => { audioRef.current.play(); setStatus("playing"); }}
          onTimeUpdate={() => setCurrentTime(audioRef.current?.currentTime ?? 0)}
          onLoadedMetadata={() => setDuration(audioRef.current?.duration ?? 30)}
          onEnded={() => { setStatus("paused"); setCurrentTime(0); }}
          onError={() => setStatus("error")}
        />
      )}

      {/* ── Top row: rank · art · info · play button ─────────── */}
      <div className="flex gap-3 items-center">

        {/* Rank */}
        <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center rounded-full bg-gray-700 text-xs font-bold text-gray-300">
          {rank}
        </div>

        {/* Album art */}
        <div className="flex-shrink-0 w-12 h-12 rounded-lg overflow-hidden bg-gray-700 flex items-center justify-center">
          {track.albumArtUrl ? (
            <img src={track.albumArtUrl} alt={track.title}
              className="w-full h-full object-cover"
              onError={(e) => { e.currentTarget.style.display = "none"; }} />
          ) : (
            <span className="text-2xl">{style.emoji}</span>
          )}
        </div>

        {/* Title · artist · badges */}
        <div className="flex-1 min-w-0">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <p className="text-sm font-semibold text-white truncate">{track.title}</p>
              <p className="text-xs text-gray-400 truncate">{track.artist}</p>
            </div>
            <span className="flex-shrink-0 text-xs text-gray-500 tabular-nums">
              {formatDuration(track.durationMs)}
            </span>
          </div>
          <div className="flex flex-wrap items-center gap-1.5 mt-1.5">
            <span className={`emotion-badge ${style.bg} ${style.text} border ${style.border}`}>
              {style.emoji} {track.primaryEmotion}
            </span>
            {track.genre && (
              <span className="emotion-badge bg-gray-700/60 text-gray-300 border border-gray-600">
                {track.genre}
              </span>
            )}
            <span className="emotion-badge bg-gray-700/60 text-gray-400 border border-gray-600">
              {track.language}
            </span>
          </div>
        </div>

        {/* ▶ / ⏸ / spinner button */}
        <button
          onClick={handlePlayPause}
          title={status === "playing" ? "Pause" : "Play 30s preview"}
          className="flex-shrink-0 w-11 h-11 rounded-full flex items-center justify-center
                     bg-brand-600 hover:bg-brand-500 text-white transition-all duration-150 shadow-lg"
        >
          {status === "loading" ? <SpinnerIcon /> :
           status === "playing" ? <PauseIcon />  : <PlayIcon />}
        </button>
      </div>

      {/* ── Audio player bar ─────────────────────────────────── */}
      {showPlayer && (
        <div className="mt-3 px-1" onClick={(e) => e.stopPropagation()}>
          {/* Progress bar */}
          <div
            className="w-full h-2.5 bg-gray-700 rounded-full cursor-pointer overflow-hidden group"
            onClick={handleSeek}
          >
            <div
              className="h-full bg-brand-500 rounded-full transition-all duration-100 group-hover:bg-brand-400"
              style={{ width: `${progressPct}%` }}
            />
          </div>
          {/* Time labels */}
          <div className="flex justify-between text-xs text-gray-500 mt-1">
            <span className="tabular-nums">{formatTime(currentTime)}</span>
            <span className="text-gray-600 text-[10px]">30s preview · iTunes</span>
            <span className="tabular-nums">{formatTime(duration)}</span>
          </div>
        </div>
      )}

      {/* Error notice */}
      {status === "error" && (
        <p className="mt-2 text-xs text-red-400 px-1" onClick={(e) => e.stopPropagation()}>
          Preview unavailable — try searching on YouTube instead.
        </p>
      )}

      {/* ── Expandable details (click card body) ─────────────── */}
      {expanded && (
        <div className="mt-3 flex flex-col gap-2" onClick={(e) => e.stopPropagation()}>
          {track.explanation && (
            <div className="text-xs text-gray-300 bg-gray-700/40 rounded-lg px-3 py-2 border border-gray-600/40">
              {track.explanation}
            </div>
          )}
          <div className="flex gap-3">
            <MiniMeter label="Energy"     value={track.energyLevel} color="bg-orange-400" />
            <MiniMeter label="Positivity" value={track.valence}     color="bg-green-400"  />
          </div>
        </div>
      )}
    </div>
  );
}

// ── Icons ──────────────────────────────────────────────────────────

function PlayIcon() {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" className="w-5 h-5" viewBox="0 0 24 24" fill="currentColor">
      <polygon points="6,3 20,12 6,21" />
    </svg>
  );
}

function PauseIcon() {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" className="w-5 h-5" viewBox="0 0 24 24" fill="currentColor">
      <rect x="5"  y="3" width="4" height="18" rx="1" />
      <rect x="15" y="3" width="4" height="18" rx="1" />
    </svg>
  );
}

function SpinnerIcon() {
  return (
    <svg className="w-5 h-5 animate-spin" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
    </svg>
  );
}

function MiniMeter({ label, value, color }) {
  const pct = Math.round((value ?? 0) * 100);
  return (
    <div className="flex items-center gap-1.5 text-xs text-gray-400">
      <span>{label}</span>
      <div className="w-16 h-1.5 bg-gray-600 rounded-full overflow-hidden">
        <div className={`h-full ${color} rounded-full`} style={{ width: `${pct}%` }} />
      </div>
      <span className="tabular-nums">{pct}%</span>
    </div>
  );
}
