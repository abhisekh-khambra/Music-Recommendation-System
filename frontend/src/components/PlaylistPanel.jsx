import React, { useEffect, useState } from "react";
import TrackCard from "./TrackCard";
import {
  getPlaylistOptions,
  getHistoryPlaylist,
  getArtistPlaylist,
  getRagaPlaylist,
} from "../services/api";

const VIEWS = [
  { id: "history", label: "From your history" },
  { id: "artist", label: "By artist" },
  { id: "raga", label: "By raga" },
];

/**
 * Playlists beyond the current mood, the way a streaming app arranges music:
 * songs this session was recommended before, a singer's songs, or a raga's songs.
 * `refreshKey` changes after each new recommendation so the history view stays current.
 */
export default function PlaylistPanel({ sessionId, refreshKey }) {
  const [view, setView] = useState("history");
  const [options, setOptions] = useState({ artists: [], ragas: {} });
  const [artist, setArtist] = useState("");
  const [raga, setRaga] = useState("");
  const [playlist, setPlaylist] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    getPlaylistOptions()
      .then((o) => {
        setOptions(o);
        setArtist((a) => a || o.artists[0] || "");
        setRaga((r) => r || Object.keys(o.ragas)[0] || "");
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    let cancelled = false;
    const load =
      view === "history" ? getHistoryPlaylist(sessionId)
      : view === "artist" ? (artist ? getArtistPlaylist(artist) : null)
      : (raga ? getRagaPlaylist(raga) : null);
    if (!load) return undefined;
    setError(null);
    load
      .then((p) => { if (!cancelled) setPlaylist(p); })
      .catch((e) => { if (!cancelled) setError(e.message); });
    return () => { cancelled = true; };
  }, [view, artist, raga, sessionId, refreshKey]);

  return (
    <div className="card">
      <h2 className="text-sm font-semibold text-gray-300 mb-3 flex items-center gap-2">
        <span>📚</span> More playlists
      </h2>

      <div className="flex gap-1 bg-gray-700/50 rounded-xl p-1 mb-3">
        {VIEWS.map((v) => (
          <button
            key={v.id}
            onClick={() => setView(v.id)}
            className={`flex-1 py-1.5 text-xs font-medium rounded-lg transition-all
              ${view === v.id ? "bg-brand-600 text-white shadow" : "text-gray-400 hover:text-white"}`}
          >
            {v.label}
          </button>
        ))}
      </div>

      {view === "artist" && (
        <select
          value={artist}
          onChange={(e) => setArtist(e.target.value)}
          className="w-full mb-3 bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-gray-200"
        >
          {options.artists.map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
      )}
      {view === "raga" && (
        <select
          value={raga}
          onChange={(e) => setRaga(e.target.value)}
          className="w-full mb-3 bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-gray-200"
        >
          {Object.entries(options.ragas).map(([r, n]) => (
            <option key={r} value={r}>Raga {r} ({n} {n === 1 ? "song" : "songs"})</option>
          ))}
        </select>
      )}

      {error && <p className="text-xs text-red-400">{error}</p>}
      {playlist && (
        <>
          <p className="text-xs text-gray-400 mb-2">{playlist.description}</p>
          <div className="flex flex-col gap-2 max-h-[28rem] overflow-y-auto pr-1">
            {playlist.tracks.map((t, i) => <TrackCard key={t.id} track={t} rank={i + 1} />)}
          </div>
        </>
      )}
    </div>
  );
}
