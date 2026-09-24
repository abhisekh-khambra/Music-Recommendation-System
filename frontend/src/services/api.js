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
 * Send a text message and receive emotion + top-10 recommendations.
 * @param {string} message
 * @param {string} sessionId
 * @returns {Promise<RecommendationResponse>}
 */
export const getTextRecommendation = (message, sessionId) =>
  client
    .post("/recommend/text", { message, sessionId })
    .then((r) => r.data);

/**
 * Send a recorded audio Blob and receive transcription + recommendations.
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
