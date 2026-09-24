import React, { useState, useCallback, useEffect } from "react";
import { v4 as uuidv4 } from "uuid";
import TextInput from "./components/TextInput";
import VoiceInput from "./components/VoiceInput";
import RecommendationList from "./components/RecommendationList";
import ChatInterface from "./components/ChatInterface";
import { getTextRecommendation, getVoiceRecommendation, getChatHistory } from "./services/api";

const SESSION_KEY = "moodtunes_session_id";

function getOrCreateSession() {
  let id = sessionStorage.getItem(SESSION_KEY);
  if (!id) {
    id = uuidv4();
    sessionStorage.setItem(SESSION_KEY, id);
  }
  return id;
}

export default function App() {
  const [sessionId] = useState(getOrCreateSession);
  const [tab, setTab] = useState("text"); // "text" | "voice"
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);
  const [history, setHistory] = useState([]);

  // Refresh chat history after every successful recommendation
  const refreshHistory = useCallback(async () => {
    try {
      const h = await getChatHistory(sessionId);
      setHistory(h);
    } catch (_) {
      // non-critical — silently ignore
    }
  }, [sessionId]);

  useEffect(() => {
    refreshHistory();
  }, [refreshHistory]);

  const handleTextSubmit = useCallback(async (message) => {
    setLoading(true);
    setError(null);
    try {
      const data = await getTextRecommendation(message, sessionId);
      setResult(data);
      await refreshHistory();
    } catch (err) {
      setError(err.message || "Something went wrong. Please try again.");
    } finally {
      setLoading(false);
    }
  }, [sessionId, refreshHistory]);

  const handleAudioReady = useCallback(async (blob) => {
    setLoading(true);
    setError(null);
    try {
      const data = await getVoiceRecommendation(blob, sessionId);
      setResult(data);
      await refreshHistory();
    } catch (err) {
      setError(err.message || "Voice processing failed. Please try again.");
    } finally {
      setLoading(false);
    }
  }, [sessionId, refreshHistory]);

  return (
    <div className="min-h-screen bg-gradient-to-br from-gray-950 via-gray-900 to-brand-900/20 font-sans">
      {/* ── Header ───────────────────────────────────────────── */}
      <header className="border-b border-gray-800 bg-gray-900/80 backdrop-blur sticky top-0 z-10">
        <div className="max-w-6xl mx-auto px-4 py-3 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <span className="text-2xl">🎵</span>
            <div>
              <h1 className="text-lg font-bold text-white tracking-tight">Mood Music</h1>
              <p className="text-xs text-gray-400 hidden sm:block">
                AI-Powered Emotion × Music Recommendations
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 text-xs text-gray-500">
            <span className="w-2 h-2 rounded-full bg-green-500 animate-pulse" />
            Groq AI Active
          </div>
        </div>
      </header>

      {/* ── Main Layout ──────────────────────────────────────── */}
      <main className="max-w-6xl mx-auto px-4 py-6 grid grid-cols-1 lg:grid-cols-2 gap-6">

        {/* ── Left Panel: Input + Chat History ─────────────── */}
        <section className="flex flex-col gap-5">
          {/* Input card */}
          <div className="card">
            {/* Tab switcher */}
            <div className="flex gap-1 bg-gray-700/50 rounded-xl p-1 mb-4">
              {[
                { id: "text",  label: "Text Input",  icon: "⌨️" },
                { id: "voice", label: "Voice Input", icon: "🎙️" },
              ].map((t) => (
                <button
                  key={t.id}
                  onClick={() => setTab(t.id)}
                  className={`flex-1 py-2 text-sm font-medium rounded-lg transition-all duration-150 flex items-center justify-center gap-1.5
                    ${tab === t.id
                      ? "bg-brand-600 text-white shadow"
                      : "text-gray-400 hover:text-white"
                    }`}
                >
                  {t.icon} {t.label}
                </button>
              ))}
            </div>

            {/* Input component */}
            {tab === "text" ? (
              <TextInput onSubmit={handleTextSubmit} isLoading={loading} />
            ) : (
              <VoiceInput onAudioReady={handleAudioReady} isLoading={loading} />
            )}

            {/* Error display */}
            {error && (
              <div className="mt-3 bg-red-900/40 border border-red-700/50 text-red-300 text-sm rounded-lg px-4 py-2">
                {error}
              </div>
            )}
          </div>

          {/* Chat history card */}
          <div className="card">
            <h2 className="text-sm font-semibold text-gray-300 mb-3 flex items-center gap-2">
              <span>💬</span> Session History
            </h2>
            <ChatInterface history={history} />
          </div>

          {/* How it works */}
          <div className="card bg-gray-800/40">
            <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wide mb-2">
              How It Works
            </h3>
            <ol className="text-xs text-gray-400 space-y-1 list-decimal list-inside">
              <li>You describe your mood in Hindi or English (text or voice)</li>
              <li>Groq LLM detects fine-grained emotion (joy, sadness, stress…)</li>
              <li>We retrieve 30 candidate songs matching your emotion from the database</li>
              <li>Groq reranks them and selects the TOP 10 best matches</li>
              <li>You get a personalised playlist with explanations</li>
            </ol>
          </div>
        </section>

        {/* ── Right Panel: Recommendations ─────────────────── */}
        <section className="flex flex-col gap-4">
          {loading && (
            <div className="card flex flex-col items-center justify-center h-48 gap-3">
              <div className="flex gap-1.5 items-end h-8">
                {[...Array(5)].map((_, i) => (
                  <div key={i} className="wave-bar" style={{ animationDelay: `${i * 0.1}s` }} />
                ))}
              </div>
              <p className="text-sm text-gray-400">
                Analyzing mood & curating your playlist…
              </p>
            </div>
          )}

          {!loading && !result && (
            <div className="card flex flex-col items-center justify-center h-64 gap-4 text-gray-500">
              <span className="text-5xl">🎧</span>
              <div className="text-center">
                <p className="font-medium text-gray-400">Your personalized playlist</p>
                <p className="text-sm mt-1">
                  Share how you're feeling to get started
                </p>
              </div>
            </div>
          )}

          {!loading && result && (
            <RecommendationList data={result} />
          )}
        </section>
      </main>

      {/* ── Footer ───────────────────────────────────────────── */}
      <footer className="border-t border-gray-800 mt-10 py-4">
        <p className="text-center text-xs text-gray-600">
          MoodTunes • Spring Boot + Spring AI + React • Powered by Groq LLaMA 3.1
        </p>
      </footer>
    </div>
  );
}
