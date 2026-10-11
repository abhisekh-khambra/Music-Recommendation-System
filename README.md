# Music Recommendation System

A conversational, emotion-aware music recommendation system. A user describes how they feel — by typing or speaking, in English or Hindi — and the system detects their emotional state, retrieves matching tracks from a MySQL catalogue through several independent strategies, fuses and reranks them, and returns a ranked playlist with a natural-language explanation for every pick, grounded only in verifiable facts about that track.

Stack: **Spring Boot 3.3 / Java 17** backend, **React 18 + Tailwind** frontend, **MySQL** storage, **Groq** (OpenAI-compatible API) for LLM chat and Whisper speech-to-text via **Spring AI**, plus a from-scratch **pure-Java acoustic analysis layer** (pitch/energy/diarization — see below) and **`ffmpeg`** for audio decoding.

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
   → every label is checked against the 12 canonical emotions
     (reference/EmotionLabels): known variants are corrected
     (NOSTALGY → NOSTALGIA, HAPPY → JOY), unknown ones dropped
   → malformed replies (e.g. "intensity": 0. nine) are repaired,
     then retried once; only then the English/Hinglish keyword
     fallback is used (marked "Fallback" in context_summary)
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
   → every song is guaranteed an explanation: any song the LLM
     left unexplained (or every song, if the LLM call fails)
     gets one assembled from its own stage-4 evidence
   │
   ▼
Ranked playlist + per-track explanation + a warm
conversational message, returned to the frontend
```

Everything in stages 2–5 is deterministic application code — only stages 1 and 6 call the LLM, and stage 6 is explicitly constrained to the evidence stage 4 already verified. This means recommendations can't drift into fabricated claims about a track ("this reminds you of your childhood") that aren't backed by an actual database fact.

**Audio is validated before speech-to-text** (voice and conversation endpoints): the upload is decoded with ffmpeg first, and rejected with HTTP 400 if it is not decodable audio or contains no voiced speech. Without this, a non-audio file surfaced as HTTP 500 and silence made Whisper hallucinate a transcript ("you") that then produced 10 recommendations.

**Voice input gets one more signal before stage 1's output is used:** `RecommendationController` also runs `com.musicrec.audio` (ffmpeg decode → pitch/energy acoustic analysis, no ML model) on the raw recording and fuses its arousal estimate into the text-based `EmotionMetadata` via `MultimodalEmotionFusionService` — real audio-based emotion evidence, not just a transcription passed through the text analyzer. The fused result carries `audio_emotion_profile` and `fusion_note` fields explaining whether/how the audio signal adjusted anything; text input has neither field since there's no audio to analyze.

## Conversation-based, Raga-centric pipeline

A second, additive pipeline (`POST /api/v1/conversation/analyze`) takes a **recorded conversation audio file between a Health Consultant and a Patient** and puts **Raga selection at the center** of the recommendation, rather than treating Raga as one more filter. The Hindi-only restriction (see below) is now enforced **system-wide** — `/recommend/text` and `/recommend/voice` also only ever return Hindi tracks — but real acoustic (audio-based) diarization/emotion analysis and Raga selection remain specific to this pipeline, since there's no "conversation" to diarize or Raga to select for a single text/voice message.

```
Conversation audio (Health Consultant + Patient)
   │
   ▼
[1] SEGMENTED SPEECH-TO-TEXT (Groq Whisper, verbose_json)   SpeechToTextService.transcribeWithSegments()
   → real per-segment start/end timestamps decoded from the audio by Whisper itself
     (not inferred by an LLM) — the unit everything downstream aligns to
   → segments whose time range contains no voiced audio are dropped (with a warning):
     Whisper hallucinates text over silence, and such a segment would become a fake
     turn and distort speaker clustering
   │
   ▼
[2] AUDIO-BASED SPEAKER DIARIZATION (pure Java, no ML model) com.musicrec.audio.SpeakerDiarizationService
   → ffmpeg decodes the clip to 16kHz mono PCM; AcousticFeatureExtractor computes
     per-frame pitch (autocorrelation, with octave-error correction) + energy (RMS);
     each Whisper segment's mean pitch/energy is clustered into 2 groups via a
     deterministic 2-means clustering — i.e. "which segments are the same voice"
     comes from the ACTUAL AUDIO SIGNAL. Pitch dominates (energy weight 0.3, since
     loudness varies per sentence); pitchless segments are left out of clustering
     and inherit the nearest speaker. The split is only reported as reliable if the
     two clusters' mean pitch differs by ≥ 1.2× (~3 semitones) and, with ≥ 4
     segments, each cluster holds at least 2 — so one voice reading both roles, or a
     single odd segment, is flagged `speakerAssignmentUncertain` rather than trusted
   │
   ▼
[3] TEXT-BASED ROLE LABELING (Groq LLM)            ConversationAnalysisService.labelRoles()
   → classifies each segment as HEALTH_CONSULTANT / PATIENT from conversational cues.
     SpeakerRoleResolver then assigns each ACOUSTIC cluster's role by majority vote of
     its segments' text labels — text only breaks "which cluster is which", it is no
     longer what splits the conversation into speaker turns (that's audio-driven now)
   │
   ▼
[4] PATIENT-FOCUSED EMOTION ANALYSIS — TEXT + AUDIO, PER-SEGMENT AND OVERALL
   → EmotionAnalysisService.analyzeBatch(): one LLM call analyzes EVERY Patient
     segment independently (not blended into one), so the conversation isn't forced
     into a single fixed emotion — see req. #3
   → com.musicrec.audio.AcousticEmotionAnalysisService: a rule-based (NOT a trained
     classifier) vocal-arousal score from pitch-variability (in semitones, so low and
     high voices are scored alike) + energy, computed for the whole Patient audio AND
     per-segment — kept explicitly separate from the text-based classification; the
     two are never silently merged into one number. No voiced audio → UNKNOWN
   → ConversationAnalysisService.buildVariability(): pure-code aggregation across
     segments — distinct emotion count, transition count, intensity variance, the
     DOMINANT emotion (most frequent; ties → higher total intensity) and the
     EMOTION DISTRIBUTION (% of patient responses per emotion, summing to 100),
     shown as bars in the conversation result screen
   │
   ▼
[5] NON-DIAGNOSTIC ANALYSIS PANEL (pure code)      ConversationAnalysisService.buildAnalysis()
   → sentiment, stress/anxiety/sadness/relaxation cue flags, and a template-built
     context sentence — deliberately code-generated, never LLM-phrased, so it can't
     drift into diagnostic language ("the patient has anxiety disorder")
   │
   ▼
[6] RAGA SELECTION (pure code, Hindi-aware)   RagaSelectionService.selectRaga(emotion, distribution, true)
   → scores every raga in the static RagaRasaCatalog by how much of the patient's
     emotional MIX it covers: the share of responses per emotion, plus a smaller
     bonus (0.25) for the overall patient emotion and 0.05 per related emotion —
     so the dominant response emotion leads but the other emotions still count.
     The reason states the coverage ("covers 75% of the patient's responses").
     Only ragas with Hindi-tagged tracks are eligible; if the best match has none,
     the substitution is stated in `warnings` instead of happening silently
   │
   ▼
[7] RAGA-BASED RETRIEVE → FUSE → EVIDENCE → RERANK → SELECT — HINDI-ONLY
   MusicRecommendationService.recommend(emotion, raga, restrictToHindi=true)
   → identical pipeline to the text/voice flow, plus the raga source, and retrieval
     also uses the distribution's other emotions (most frequent first), BUT every
     retrieval source (and the all-empty fallback) is pre-filtered to
     language=Hindi BEFORE fusion — a non-Hindi track can never reach the LLM
     SELECT call, let alone the final list. If fewer than 10 eligible Hindi
     tracks exist for this profile, the response says so explicitly rather than
     padding with non-Hindi tracks (see Hindi-only section below)
   │
   ▼
Transcript with speaker turns (timestamped) + audio-based arousal profile +
per-segment emotional-variability profile with dominant emotion and distribution +
non-diagnostic analysis + selected Raga & coverage reason + ranked Hindi-only
top-10 songs + per-track explanations
```

The only LLM calls are stage-1 transcription, stage-3 role labeling, and stage-4's one batched per-segment emotion call (plus the shared SELECT call in stage 7); diarization, acoustic arousal, variability aggregation, Raga selection, and the Hindi-only filter are all deterministic code.

**Known limitation — acoustic diarization quality depends on the recording.** The 2-means clustering over pitch/energy works well when the two speakers have reasonably distinct vocal registers and the recording is more than a few seconds — it's a lightweight, explainable technique (no `pyannote.audio` or other trained diarization model), not state-of-the-art diarization. `SpeakerDiarizationService` reports `reliable=false` rather than a confident-looking but wrong split when the clusters overlap (centroid distance vs. within-cluster spread), when their mean pitch differs by less than 1.2×, or when one "speaker" is a single segment; `ConversationController` then falls back to per-segment text-role labels and says so in `warnings`/`diarizationMethod`, and the user can correct roles via `/conversation/reassign-roles`. In testing, male/female pairs (including noisy, phone-band audio) separated confidently and correctly, while **two speakers of the same gender are flagged uncertain rather than separated** — separating those would need a speaker-embedding model, not pitch.

**Known limitation — acoustic arousal, not full audio emotion recognition.** Pitch variability + energy reliably indicate *arousal* (how vocally intense speech sounds), not *valence* or a specific emotion category — `AudioEmotionProfileDTO.limitations` states this on every response. Arousal from a single clip has no per-speaker baseline, so only *relative* comparisons are reliable (excited speech scores above calm speech from the same voice); absolute levels can read HIGH for naturally expressive voices. The system never lets the acoustic signal override the text-based emotion category; `MultimodalEmotionFusionService`/the conversation controller only use it to nudge intensity (with the adjustment always recorded in `fusionNote`) or flag a text/audio disagreement.

**Known limitation — ffmpeg is a new external prerequisite.** There's no pure-Java decoder for WebM/Opus or MP3, so `AudioTranscodingService` shells out to `ffmpeg` (configurable via `app.audio.ffmpeg-path`) rather than pulling in a large native-binary-bundling Maven dependency. Set its location with the `FFMPEG_PATH` environment variable (the `application.yml` default is the WinGet install path on the original development machine, so on any other machine set `FFMPEG_PATH`, or `app.audio.ffmpeg-path`). If `ffmpeg` can't be run, acoustic analysis and diarization degrade gracefully to text-only (a warning is added to the response) — transcription itself is unaffected, since Whisper decodes audio server-side on Groq's end, not locally.

**Known limitation — small instrumental raga catalogue, now supplemented with Hindi vocal tracks.** The original ~22 purely-instrumental raga recordings (`language='Instrumental'`, no lyrics) are NOT eligible for the Hindi-only conversation pipeline's final list — they still inform raga selection (every raga's rasa/description/reason is unchanged) but can't appear in a Hindi-restricted Top-10. To make raga-based Hindi results achievable, `data.sql` now also includes a handful of real Hindi FILM songs with a commonly-cited raga basis (Albela Sajan → Bhairav, Tadap Tadap → Malkauns, Silsila Ye Chaahat Ka → Yaman, Man Mohana → Darbari Kanada) — attribution drawn from composer-interview/film-music-journalism sources, not an independently verified musicological study, same caveat as `RagaRasaCatalog` itself. Measured in the database: **only 4 of the 22 ragas (Yaman, Darbari Kanada, Malkauns, Bhairav) have any Hindi track, and 47 of the 51 Hindi tracks have no raga tag**, so raga selection can only land on those four for Hindi output and most songs come from the shared emotion+metadata sources rather than an exact raga match. Raga tags must come from a verified source, so none were invented; tagging the existing Hindi songs is the most valuable data task.

## Hindi-only restriction (system-wide)

**Every** recommendation endpoint — `/recommend/text`, `/recommend/voice`, and `/conversation/analyze` — recommends exclusively Hindi-language tracks. There is no multilingual path left: an English-speaking user describing their mood in English still only ever gets Hindi songs back.

Enforcement happens centrally in `MusicRecommendationService`, not per-caller:
- Every `recommend(...)` overload defaults `restrictToHindi=true` — a caller would have to explicitly pass `false` to get the old unrestricted behavior, and no controller does.
- Every one of the ~8 retrieval sources (exact emotion+language match, emotion-only, raga, per-secondary-emotion, per-mood-keyword, valence/energy range, TF-IDF dense, and the all-sources-empty fallback) is filtered down to `language='Hindi'` tracks **before** fusion — not after. The LLM SELECT call's candidate pool is therefore Hindi-only by construction; it has no non-Hindi track to even consider.
- `RagaSelectionService.selectRaga(emotion, restrictToHindi=true)` (used by the conversation pipeline) only counts a raga as "available" if it has Hindi-tagged tracks.
- If fewer than `app.recommendation.top-n-results` (default 10) eligible Hindi tracks are found for a given profile, the response's `warnings` array says so explicitly (e.g. *"Only 4 verified Hindi-language track(s) matched... catalogue needs more eligible Hindi tracks..."*) and returns only the verified matches — it never pads the list with a non-Hindi track to hit 10.
- `MusicRecommendationServiceHindiFilterTest` (backend) verifies this with a mocked pool where 5 of 7 candidates are English — the test asserts zero English tracks ever reach the final result, and a second test confirms the plain `recommend(emotion)` overload (used by text/voice) is Hindi-only by default too. Separate tests also prove a track with no language metadata, or a non-exact label (e.g. "Hinglish"), is excluded rather than assumed eligible.

## Evaluation framework

`com.musicrec.evaluation` provides two reusable metric calculators, scoped to what's actually required — Raga-level and song-level recommendation evaluation, including NDCG:

| Class | Metrics | For |
|---|---|---|
| `RagaMappingEvaluator` | Top-1 accuracy, Top-k accuracy, and an agreement rate against `RagaRasaCatalog` | Raga-level recommendation evaluation. The agreement rate checks internal consistency with the documented reference table used by `RagaSelectionService` — **not** correctness against an independent expert-validated source (none exists in this project). Top-1/Top-k need real expected-Raga ground truth to produce a number. |
| `RankingMetrics` | Precision@K, Recall@K (needs total-relevant count), NDCG@K | Song-level Top-10 ranking evaluation |

**What's real here vs. what isn't:** the metric *arithmetic* is unit-tested against small, hand-computed examples (`RankingMetricsTest`, `RagaMappingEvaluatorTest`) — that verifies the formulas are implemented correctly. **No expert-validated Raga-mapping dataset or relevance-judgment set is wired into this project.** Per the project's own no-fabrication requirement, this README does not report any Top-k/Precision/NDCG number for the actual system, because none has been measured against real ground truth. To get a real, publishable number: call `RagaMappingEvaluator.top1Accuracy(...)` / `RankingMetrics.*` from a small driver fed with a real labeled dataset you supply or annotate.

## Project structure

```
backend/
  src/main/java/com/musicrec/
    controller/    REST endpoints (RecommendationController, ConversationController, HealthController)
    service/       Pipeline stages — see architecture above, plus ConversationAnalysisService, RagaSelectionService,
                    SpeakerRoleResolver (merges audio diarization + text role labels), MultimodalEmotionFusionService
    audio/         Pure-Java acoustic layer — AudioTranscodingService (ffmpeg), AcousticFeatureExtractor
                    (pitch/energy DSP), AcousticEmotionAnalysisService (rule-based arousal), SpeakerDiarizationService
                    (2-means acoustic clustering). No ML model anywhere in this package.
    evaluation/    Raga-level and song-level metric calculators — see Evaluation framework section above
    dto/           Request/response/emotion/evidence/conversation/audio data shapes
    entity/        JPA entities (Track, User, ChatHistory)
    repository/    Spring Data JPA repositories
    reference/     RagaRasaCatalog — static raga ↔ rasa/emotion reference table;
                    EmotionLabels — the 12 canonical emotion labels + variant correction
    config/        Spring AI (Groq) client config, CORS, DB seeding
    exception/     Global exception handler
  src/test/java/com/musicrec/   Unit tests (77) — service/, audio/, controller/, reference/, evaluation/
  src/main/resources/application.yml   All runtime config (DB, Groq model, pool sizes, upload limits, ffmpeg path)

frontend/
  src/components/  ChatInterface, TextInput, VoiceInput, ConversationInput, RecommendationList,
                    ConversationResult, TrackCard
  src/services/api.js   Backend API client

e2e/
  run_e2e.py                    Automated end-to-end suite (36 scenarios) against the running backend
  make_fixtures.ps1             Test audio with Windows desktop voices + edge cases (silence, fake/empty files)
  make_realistic_fixtures.py    Realistic test audio with neural voices (Hindi, Hinglish, same-gender, noisy, long arc)
  fixtures/, results/           Generated audio + ground-truth manifests, and run results (gitignored)

docs/
  TEST_REPORT.md                Test results, defects found/fixed, limitations
  PROFESSOR_REQUIREMENTS_AUDIT.md   Requirement-by-requirement compliance audit

database/
  schema.sql       MySQL schema reference (data.sql, auto-loaded by DataInitializer, is the authoritative/current
                    seed set — 96 tracks: 21 English, 51 Hindi (incl. the 32-track catalogue expansion described
                    above), 24 instrumental Hindustani classical/raga; schema.sql itself is a stale manual
                    reference, not kept byte-for-byte in sync)
```

## Running it

**Prerequisites:** Java 17, Node 18+, MySQL running locally, a [Groq API key](https://console.groq.com), and **`ffmpeg`** (location set via `FFMPEG_PATH`) (for the conversation pipeline's acoustic analysis/diarization — see the "ffmpeg is a new external prerequisite" limitation above; everything else works without it).

```bash
# 1. Database — schema auto-creates/updates via Hibernate on boot, and data.sql auto-seeds
#    on first run (DataInitializer only seeds when the tracks table is EMPTY). If you already
#    had the database seeded before this update, the new Hindi tracks won't appear until you
#    either drop the table (`DROP TABLE tracks;` then restart) or insert the new rows manually
#    from the bottom of backend/src/main/resources/data.sql.

# 2. Backend
# Create backend/.env (gitignored, never committed) with:
#   GROQ_API_KEY=your_key_here
# and set FFMPEG_PATH=<full path to ffmpeg.exe> in your environment
# Spring Boot loads it automatically on startup — no need to export it
# in your shell each session. (A real OS env var, if set, still wins.)
cd backend
mvn spring-boot:run          # or use start-backend.bat on Windows
# → http://localhost:8080

# 3. Frontend
cd frontend
npm install
npm start                    # or use start-frontend.bat
# → http://localhost:3000
```

DB connection defaults to `jdbc:mysql://localhost:3306/music_rec_db` (root, configurable via `DB_USERNAME`/`DB_PASSWORD` env vars). The Groq chat model, pool sizes (`candidate-pool-size`, `rerank-pool-size`, `top-n-results`), and the ffmpeg path (`app.audio.ffmpeg-path`) are all in `backend/src/main/resources/application.yml`.

**Unit tests:** `cd backend && mvn test` runs 77 unit tests — Raga selection and distribution weighting, variability/dominant emotion, acoustic feature extraction and diarization (synthetic audio, including regression cases replaying values measured from real failures), emotion-label correction, malformed-LLM-reply handling, keyword fallback, audio-upload validation, guaranteed explanations, the evaluation metric arithmetic, and the Hindi-only guarantee. None need a DB, Groq key, or ffmpeg.

**End-to-end tests:** with the backend running, generate test audio and run the suite against the live API:

```bash
powershell -ExecutionPolicy Bypass -File e2e/make_fixtures.ps1 -Ffmpeg <path to ffmpeg.exe>
pip install edge-tts==7.2.8   # realistic neural voices; sends only the scripted lines to Microsoft's TTS service
python e2e/make_realistic_fixtures.py --ffmpeg <path to ffmpeg.exe>
python e2e/run_e2e.py --backend-log <path to backend log>          # all 36 scenarios
python e2e/run_e2e.py --only realistic --backend-log <path>        # or one group: text, voice, conversation, realistic, system
```

Every expected result is fixed before the run, and speaker roles are checked against ground-truth turn times. A full run takes ~25 minutes and ~150k Groq tokens; the free tier allows 200k tokens/day, so a second full run on the same day will hit the limit and fall back to keyword analysis. Results go to `e2e/results/`; see [docs/TEST_REPORT.md](docs/TEST_REPORT.md) for the latest results and analysis.

> **Note on Groq models:** Groq's available model catalog changes over time and models get deprecated. If you see `model_not_found` in the logs, run `GET https://api.groq.com/openai/v1/models` with your key to see what's currently available and update `spring.ai.openai.chat.options.model` in `application.yml`. If you land on a reasoning-style model (e.g. an `openai/gpt-oss-*` family model), keep `reasoning-effort: low` set — those models spend part of their token budget on hidden reasoning before the answer, and without a low effort setting they can burn through `max-tokens` before finishing the required JSON.

## API

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/recommend/text` | `{ "message": "...", "sessionId"?: "..." }` → emotion + **Hindi-only** ranked tracks + explanations. |
| POST | `/api/v1/recommend/voice` | multipart `audio` file → transcribed via Groq Whisper + acoustic arousal analysis, fused into one emotion profile (`emotion.audio_emotion_profile`, `emotion.fusion_note`), then same **Hindi-only** pipeline. HTTP 400 for non-audio, empty or silent uploads. |
| POST | `/api/v1/conversation/analyze` | multipart `audio` file (Health Consultant + Patient conversation, max 25MB) → timestamped transcript + audio-diarized speaker turns + audio-based arousal profile + per-segment emotional-variability profile (`emotionalVariability.dominantEmotion`, `emotionalVariability.emotionDistribution`) + non-diagnostic patient analysis + selected Raga with coverage reason + **Hindi-only** top-10 Raga-based recommendations + `speakerAssignmentUncertain` flag. HTTP 400 for non-audio, empty or silent uploads. |
| POST | `/api/v1/conversation/reassign-roles` | `{ "sessionId", "turns": [{order, speakerRole, text, startMs, endMs}, ...] }` → recomputes patient text, emotion, Raga, and the Hindi-only Top 10 from the user's corrected speaker roles — no audio re-upload needed. |
| GET | `/api/v1/history/{sessionId}` | past chat turns for a session (includes `selectedRaga`/`speakerTurns` for conversation-type entries) |
| GET | `/api/v1/tracks?emotion=&language=` | browse the full track catalogue, optionally filtered |
| GET | `/api/v1/health` | health check |

**Known gaps if closing this further is ever wanted:** more retrieval sources for niwatori-parity (e.g. genre, tempo-range, popularity-tier, artist-similarity sources), and real multimodal embeddings for Semiintelligencn-parity (would need a hosted embedding API or local ONNX models — a real infra decision, not a small change).
