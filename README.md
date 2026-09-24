# Music Recommendation System

A conversational, emotion-aware music recommendation system. A user describes how they feel — by typing or speaking, in English or Hindi — and the system detects their emotional state, retrieves matching tracks from a MySQL catalogue through several independent strategies, fuses and reranks them, and returns a ranked playlist with a natural-language explanation for every pick, grounded only in verifiable facts about that track.

Stack: **Spring Boot 3.3 / Java 17** backend, **React 18 + Tailwind** frontend, **MySQL** storage, **Groq** (OpenAI-compatible API) for LLM chat and Whisper speech-to-text via **Spring AI**.

## How it works

A request goes through two LLM calls and three code-only stages:

```
User text/voice
   │
   ▼
[1] EMOTION ANALYSIS (Groq LLM)         EmotionAnalysisService
   → primary/secondary emotion, intensity, language,
     mood keywords, and an LLM-expanded set of related
     emotions/tags used to widen retrieval
   │
   ▼
[2] RETRIEVE                            MusicRecommendationService.retrieve()
   → 6 independent MySQL query strategies, each an
     independent "source": exact emotion+language match,
     emotion-only match, one query per secondary emotion,
     one per mood keyword, a valence/energy range match,
     and a from-scratch TF-IDF cosine-similarity dense
     retrieval (SemanticRetrievalService) over the whole
     catalogue — a hybrid lexical + dense pool
   │
   ▼
[3] FUSE — Weighted Reciprocal Rank Fusion   MusicRecommendationService.rrf()
   → score(track) = Σ over sources  weight(source) / (k + rank)
     High-precision sources (exact emotion+language match)
     are weighted above fuzzy ones (a single keyword tag or
     the dense TF-IDF source), so two high-precision sources
     agreeing outranks one weak source repeating itself.
   │
   ▼
[4] PROPOSE + ASSIGN evidence (pure code, no LLM)  EvidenceGroundingService
   → for every candidate, generates atomic, code-verified
     facts by comparing its real DB fields against the
     user's profile (emotion match, mood-tag match,
     valence/energy fit, language match, retrieval
     consensus...), then keeps the strongest 3 per track.
     Nothing here can be hallucinated — it's plain
     comparisons against real columns.
   │
   ▼
[5] LIGHTWEIGHT RERANK (pure code, no LLM)   LightweightRerankerService
   → shortlists the fused pool (default 30 → 15) by a
     blend of normalized RRF score and evidence strength,
     so only the strongest candidates reach the LLM call
   │
   ▼
[6] SELECT (Groq LLM, evidence-constrained)   MusicRecommendationService.select()
   → one LLM call picks the top 10 and writes one
     explanation sentence per track — constrained by the
     prompt to cite ONLY that track's assigned evidence,
     never invent a connection that isn't in the evidence list
   │
   ▼
Ranked playlist + per-track explanation + a warm
conversational message, returned to the frontend
```

Everything in stages 2–5 is deterministic application code — only stages 1 and 6 call the LLM, and stage 6 is explicitly constrained to the evidence stage 4 already verified. This means recommendations can't drift into fabricated claims about a track ("this reminds you of your childhood") that aren't backed by an actual database fact.

## Project structure

```
backend/
  src/main/java/com/musicrec/
    controller/    REST endpoints (RecommendationController, HealthController)
    service/       Pipeline stages — see architecture above
    dto/           Request/response/emotion/evidence data shapes
    entity/        JPA entities (Track, User, ChatHistory)
    repository/    Spring Data JPA repositories
    config/        Spring AI (Groq) client config, CORS, DB seeding
    exception/     Global exception handler
  src/main/resources/application.yml   All runtime config (DB, Groq model, pool sizes)

frontend/
  src/components/  ChatInterface, TextInput, VoiceInput, RecommendationList, TrackCard
  src/services/api.js   Backend API client

database/
  schema.sql       MySQL schema + ~50 seed tracks (English + Hindi/Bollywood)
```

## Running it

**Prerequisites:** Java 17, Node 18+, MySQL running locally, a [Groq API key](https://console.groq.com).

```bash
# 1. Database — schema auto-creates/updates via Hibernate on boot,
#    or run database/schema.sql manually against MySQL if you want the seed tracks.

# 2. Backend
export GROQ_API_KEY=your_key_here
cd backend
mvn spring-boot:run          # or use start-backend.bat on Windows
# → http://localhost:8080

# 3. Frontend
cd frontend
npm install
npm start                    # or use start-frontend.bat
# → http://localhost:3000
```

DB connection defaults to `jdbc:mysql://localhost:3306/music_rec_db` (root, configurable via `DB_USERNAME`/`DB_PASSWORD` env vars). The Groq chat model and pool sizes (`candidate-pool-size`, `rerank-pool-size`, `top-n-results`) are all in `backend/src/main/resources/application.yml`.

> **Note on Groq models:** Groq's available model catalog changes over time and models get deprecated. If you see `model_not_found` in the logs, run `GET https://api.groq.com/openai/v1/models` with your key to see what's currently available and update `spring.ai.openai.chat.options.model` in `application.yml`. If you land on a reasoning-style model (e.g. an `openai/gpt-oss-*` family model), keep `reasoning-effort: low` set — those models spend part of their token budget on hidden reasoning before the answer, and without a low effort setting they can burn through `max-tokens` before finishing the required JSON.

## API

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/recommend/text` | `{ "message": "...", "sessionId"?: "..." }` → emotion + ranked tracks + explanations |
| POST | `/api/v1/recommend/voice` | multipart `audio` file → transcribed via Groq Whisper, then same pipeline |
| GET | `/api/v1/history/{sessionId}` | past chat turns for a session |
| GET | `/api/v1/tracks?emotion=&language=` | browse the full track catalogue, optionally filtered |
| GET | `/api/v1/health` | health check |

## Relationship to the RecSys 2026 Music-CRS challenge

This project independently arrived at an architecture that overlaps with three RecSys 2026 Music-CRS / TalkPlay challenge submissions. It is not a reproduction of any of them — no code or paper text was used — but it's worth naming the overlap and the gaps honestly:

| Paper | Core idea | Status here |
|---|---|---|
| **"Two Views, One Voice"** (team swyoo, 3rd place, Blind-B track) | Decouples retrieval from response generation; hybrid lexical+dense pool; propose-assign-select for evidence-grounded responses | **Implemented.** `EvidenceGroundingService` does PROPOSE+ASSIGN as pure code, fully decoupled from the `select()` LLM call; retrieval mixes 5 exact-match ("lexical") sources with 1 TF-IDF ("dense") source. |
| **"Multi-Source Retrieve-Fuse-Rerank"** (team niwatori) | 14 independent retrieval sources fused via RRF, retaining source + rank info | **Partially implemented.** Same retrieve→fuse→rerank shape, RRF retains per-source rank, but only ~6 source *types* (expanding to 6–9 actual sources at runtime) are wired, not 14. |
| **"Multi-Modal Semantic Expansion with Constrained LLM Reranking"** (team Semiintelligencn) | 3-stage pipeline: multimodal (audio/visual/text) retrieval fused via **weighted** RRF, then a lightweight reranker, then LLM response generation | **Partially implemented.** Weighted RRF and a distinct lightweight-reranker stage before the LLM call are both implemented (`LightweightRerankerService`). Multimodal retrieval (audio/album-art embeddings) is **not implemented** — this catalogue only stores `preview_url`/`album_art_url` strings, not audio/image bytes, and adding real audio/vision embeddings would need new infrastructure (downloading media, running embedding models, storage, ongoing compute cost) that was deliberately scoped out here. Semantic expansion is done, but text-only (LLM-expanded emotions/mood-tags), not multimodal. |

**Known gaps if closing this further is ever wanted:** more retrieval sources for niwatori-parity (e.g. genre, tempo-range, popularity-tier, artist-similarity sources), and real multimodal embeddings for Semiintelligencn-parity (would need a hosted embedding API or local ONNX models — a real infra decision, not a small change).
