import axios from "axios";

const BASE_URL = "http://localhost:8080/api/v1";

const client = axios.create({
  baseURL: BASE_URL,
  timeout: 60000, // 60 s — LLM calls can take a few seconds
});

// ── Request interceptor: log outgoing calls in dev ────────────
client.interceptors.request.use((config) => {
  if (process.env.NODE_ENV === "development") {
    console.debug(`[API] ${config.method?.toUpperCase()} ${config.url}`);
  }
  return config;
});

// ── Response interceptor: normalise errors ───────────────────
client.interceptors.response.use(
  (res) => res,
  (error) => {
    const message =
      error.response?.data?.message ||
      error.response?.data ||
      error.message ||
      "Unknown error";
    console.error("[API Error]", message);
    return Promise.reject(new Error(String(message)));
  }
);

/**
 * Send a text message and receive emotion + top-10 Hindi-only recommendations.
 * @param {string} message
 * @param {string} sessionId
 * @returns {Promise<RecommendationResponse>}
 */
export const getTextRecommendation = (message, sessionId) =>
  client
    .post("/recommend/text", { message, sessionId })
    .then((r) => r.data);

/**
 * Send a recorded audio Blob and receive transcription + Hindi-only recommendations.
 * @param {Blob} audioBlob
 * @param {string} sessionId
 * @returns {Promise<RecommendationResponse>}
 */
export const getVoiceRecommendation = (audioBlob, sessionId) => {
  const form = new FormData();
  form.append("audio", audioBlob, "recording.webm");
  if (sessionId) form.append("sessionId", sessionId);
  return client
    .post("/recommend/voice", form, {
      headers: { "Content-Type": "multipart/form-data" },
    })
    .then((r) => r.data);
};

/**
 * Send a recorded two-speaker conversation audio file (Health Consultant + Patient)
 * and receive the full Raga-centric pipeline result: timestamped speaker turns
 * (audio-diarized), audio-based arousal profile, per-segment emotional variability,
 * non-diagnostic patient analysis, selected Raga + reason, and Hindi-only top-10.
 * @param {Blob|File} audioFile
 * @param {string} sessionId
 * @returns {Promise<ConversationRecommendationResponse>}
 */
export const getConversationAnalysis = (audioFile, sessionId) => {
  const form = new FormData();
  form.append("audio", audioFile, audioFile.name || "conversation.webm");
  if (sessionId) form.append("sessionId", sessionId);
  return client
    .post("/conversation/analyze", form, {
      headers: { "Content-Type": "multipart/form-data" },
      timeout: 120000, // conversation audio can take longer to transcribe + analyze
    })
    .then((r) => r.data);
};

/**
 * Req. #3 — resubmit the SAME turns with user-corrected speaker roles (no audio
 * re-upload). Recomputes patient text, emotion, Raga selection, and the Hindi-only
 * Top 10 from the correction.
 * @param {Array<{order:number, speakerRole:string, text:string, startMs:number, endMs:number}>} turns
 * @param {string} sessionId
 * @returns {Promise<ConversationRecommendationResponse>}
 */
export const reassignConversationRoles = (turns, sessionId) =>
  client
    .post("/conversation/reassign-roles", { turns, sessionId })
    .then((r) => r.data);

/**
 * Fetch chat history for a given session.
 * @param {string} sessionId
 * @returns {Promise<ChatHistory[]>}
 */
export const getChatHistory = (sessionId) =>
  client.get(`/history/${sessionId}`).then((r) => r.data);

/**
 * Fetch all songs from the catalog (sorted by popularity).
 * @param {Object} filters  optional — { emotion: "JOY", language: "Hindi" }
 * @returns {Promise<TrackDTO[]>}
 */
export const getAllTracks = (filters = {}) => {
  const params = {};
  if (filters.emotion)  params.emotion  = filters.emotion;
  if (filters.language) params.language = filters.language;
  return client.get("/tracks", { params }).then((r) => r.data);
};
