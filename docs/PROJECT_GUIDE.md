# MoodTunes: Technical Guide for the Research Meeting

Everything here was checked against the source code on 2026-10-12. File paths are relative to the repository root; Java classes live under `backend/src/main/java/com/musicrec/`. Where something isn't implemented, or a result hasn't been measured, this guide says so.

**Contents**
1. Project overview and research motivation
2. Architecture and technology stack
3. The three input modes, end to end
4. The pipeline, component by component
5. Evaluation: what's done, preliminary or planned
6. Gaps against the professor's requirements
7. Presentation material: 2-minute intro, 10–15-minute script, likely questions, hard questions, what to fix

---

## 1. Project overview and research motivation

### What MoodTunes is
MoodTunes recommends a **Top-10 playlist of Hindi songs** from a person's emotional state. Input can be typed text, a voice recording, or, the main research focus, a **recorded conversation between a Health Consultant and a Patient**. In conversation mode the system:

1. separates the two speakers,
2. analyses only the patient's answers,
3. gives each answer its own emotion, from the words and from the voice,
4. turns those answers into an **emotion distribution** (e.g. 50% sadness, 25% nostalgia, 25% calm),
5. chooses **ragas** from Hindustani rasa theory for that mix (one overall, and one per emotion),
6. builds a Top-10 that is **split by emotion share** (50% sad → 5 of 10 songs), each song with an explanation.

### What makes it different from a conventional recommender
| Conventional emotion-aware recommender | MoodTunes |
|---|---|
| One input, one emotion label | A two-speaker conversation; one emotion **per patient answer**, plus how the emotion varies |
| Genre/valence matching | Songs are also chosen through **raga–rasa theory** (classical Indian music's link between ragas and emotions) |
| The dominant emotion fills the playlist | The playlist is **proportional** to the emotional mix |
| Opaque ranking | Every song's explanation is built from **verified catalogue facts** ("evidence"), not free LLM text |

### Research problem and questions
- **Problem:** Can a therapist's recorded conversation drive a culturally grounded, explainable music recommendation that reflects how the patient's emotions change during the conversation?
- **RQ1:** How accurately can per-answer emotions be identified from patient speech, using words, voice, or both?
- **RQ2:** Does the conversation's emotion distribution lead to an appropriate raga (Top-1/Top-3)?
- **RQ3:** How relevant and well-ranked is the Top-10 (Precision@10, NDCG@10, song-level raga and emotion accuracy)?
- **RQ4:** Can the two speakers be separated reliably from audio alone?

### Candidate contributions (be modest; see §6.6)
1. A conversation-level pipeline: speaker separation → patient-only, per-answer emotion → emotion distribution.
2. Emotion distribution driving **both** raga choice and a proportional playlist, with one raga per emotion share.
3. Evidence-grounded LLM selection: the LLM may only cite facts computed by code.
4. A two-part evaluation protocol (transcript level and audio level) with code and labels in the repository.

---

## 2. Architecture and technology stack

Block diagram: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) (Mermaid; renders on GitHub/VS Code).

```
React UI (frontend/)  ──HTTP/JSON──►  Spring Boot API (backend/)  ──JPA──►  MySQL (tracks, users, chat_history)
                                         │   │   │
                                         │   │   └─► ffmpeg (decode audio to 16 kHz PCM)
                                         │   └─────► Groq API: Whisper large-v3 (speech-to-text) + LLM (openai/gpt-oss-120b)
                                         └─────────► ser-service/ (local Python): voice emotion + speaker voiceprints
```

| Technology | Where | Why it's used |
|---|---|---|
| **React 18 + Tailwind + axios** | `frontend/src/` (`App.jsx`, `components/*`, `services/api.js`) | Three input tabs, recording/upload, result views, playlist panel |
| **Java 17 + Spring Boot 3.3.4** | `backend/` | REST API, dependency injection, validation, scheduling of the pipeline stages |
| **Spring AI** (OpenAI-compatible client) | `config/SpringAIConfig.java` | Talks to Groq through three `ChatClient` beans with separate system prompts: `emotionChatClient`, `rerankerChatClient`, `conversationChatClient` |
| **Groq LLM `openai/gpt-oss-120b`** | `application.yml` (`temperature 0.7`, `max-tokens 4096`, `reasoning-effort low`) | Emotion analysis, per-segment emotions, speaker-role labelling, final song selection and explanations |
| **Whisper large-v3 (via Groq)** | `service/SpeechToTextService.java` | Speech-to-text; `verbose_json` gives **real per-segment timestamps** for the conversation pipeline |
| **ffmpeg** | `audio/AudioTranscodingService.java` | Decodes any upload (WebM/MP3/M4A/WAV) to 16 kHz mono 16-bit PCM for local acoustic analysis |
| **MySQL + Hibernate (JPA)** | `entity/*`, `repository/*`, `ddl-auto: update` | Tables `tracks`, `users`, `chat_history`; seeded by `data.sql` + `data_raga_additions.sql` (`config/DataInitializer.java`) |
| **Python 3, FastAPI, PyTorch (CPU), Hugging Face transformers** | `ser-service/` | Local speech emotion recognition (`superb/wav2vec2-base-superb-er`) and speaker voiceprints (`microsoft/wavlm-base-plus-sv`); audio never leaves the machine |
| **JUnit 5 + Mockito + AssertJ, pytest** | `backend/src/test/`, `ser-service/tests/`, `evaluation/tools/` | 171 + 11 + 4 automated tests |

### Database tables
| Table | Entity | Key columns |
|---|---|---|
| `tracks` | `entity/Track.java` | title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions (JSON), mood_tags (JSON), raga, popularity |
| `users` | `entity/User.java` | username, email |
| `chat_history` | `entity/ChatHistory.java` | session_id, user_message, detected_emotion, emotion_intensity, recommended_track_ids (JSON), input_type, language, selected_raga, speaker_turns (JSON) |

Catalogue today: **121 tracks, 76 Hindi**. 25 Hindi film songs carry a raga attribution from a cited source (`database/raga_tagged_hindi_songs.json`), so **17 of the 22 ragas** in the catalogue have at least one Hindi song.

### API endpoints
| Method & route | Controller method | Purpose |
|---|---|---|
| `POST /api/v1/recommend/text` | `RecommendationController.recommendByText` | Text mode |
| `POST /api/v1/recommend/voice` (+ `emotionSource`) | `RecommendationController.recommendByVoice` | Voice mode |
| `POST /api/v1/conversation/analyze` (+ `emotionSource`) | `ConversationController.analyzeConversation` | Conversation mode |
| `POST /api/v1/conversation/reassign-roles` | `ConversationController.reassignRoles` | User corrects speaker labels; recomputed without re-uploading |
| `GET /api/v1/playlists/{options, history/{id}, artist?name=, raga/{raga}}` | `PlaylistController` | Playlists beyond the current mood |
| `GET /api/v1/history/{sessionId}`, `GET /api/v1/tracks`, `GET /api/v1/health` | `RecommendationController`, `HealthController` | History, catalogue, status |
| `POST /api/v1/evaluation/run`, `POST /api/v1/evaluation/run-audio` | `EvaluationController` | Transcript and audio evaluations |
| `POST /analyze` (port 8090) | `ser-service/ser_service/app.py` | Voice emotion + voiceprints per segment |

---

## 3. The three input modes, end to end

### 3.1 Text mode: `POST /recommend/text`
1. `EmotionAnalysisService.analyze(text)` → one LLM call returning JSON: `primary_emotion` (one of 12), `secondary_emotions`, `intensity` 0–1, `language`, `mood_keywords`, `expanded_emotions`, `expanded_mood_tags`.
2. `MusicRecommendationService.recommend(emotion)` → retrieve → fuse → evidence → rerank → LLM select (§4.7–4.10).
3. The turn is saved to `chat_history`; the response returns the emotion, 10 songs and an explanation per song.

### 3.2 Voice mode: `POST /recommend/voice`
1. **Decode and validate:** ffmpeg → 16 kHz PCM; no speech or not audio → HTTP 400 (`decodeAndValidate`).
2. **Transcribe:** Whisper (`SpeechToTextService.transcribe`).
3. **Emotion from the words:** same as text mode.
4. **Vocal arousal:** pitch/energy features (`AcousticFeatureExtractor`, `AcousticEmotionAnalysisService`) can raise intensity (`MultimodalEmotionFusionService.fuse`).
5. **Emotion from the voice:** the speech-emotion model on the whole clip (`SpeechEmotionClient.analyze`), fused with the words (`MultimodalEmotionFusionService.fuseSpeechEmotion`) by the `emotionSource` choice: TEXT / VOICE / FUSED.
6. Same recommendation pipeline as text.

### 3.3 Conversation mode: `POST /conversation/analyze` (main focus)
`ConversationController.analyzeConversation` → `ConversationPipelineService.run`.

| Step | What happens | Code |
|---|---|---|
| 1. Upload & validation | Multipart audio ≤ 25 MB; decoded with ffmpeg; rejected if not audio or silent | `validateAudio`, `decodeOrEmpty`, `AcousticFeatureExtractor.containsSpeech` |
| 2. Speech-to-text | Whisper `verbose_json` → segments with start/end ms | `SpeechToTextService.transcribeWithSegments` |
| 3. Drop hallucinated segments | Segments whose time range has no voiced audio are removed (Whisper invents words over silence) | `dropSegmentsWithoutSpeech` |
| 4. Voice emotion + voiceprints | Per segment: 4-class voice emotion and a WavLM voiceprint | `SpeechEmotionClient.analyze(pcm, spans, true)` |
| 5. Speaker separation | Voiceprint clustering, else pitch clustering; reliability checked | `SpeakerDiarizationService.diarizeByEmbeddings`, `diarize`, `choose` |
| 6. Role labelling | LLM labels each segment Consultant/Patient from text cues; each voice cluster takes the majority label | `ConversationAnalysisService.labelRoles`, `SpeakerRoleResolver.resolve` |
| 7. Patient-only analysis | Consultant turns ignored for emotion | `ConversationPipelineService.run` |
| 8. Emotion per answer | One batched LLM call (each answer analysed independently), fused with that answer's voice emotion | `EmotionAnalysisService.analyzeBatch`, `SpeechEmotionFusion.fuse` |
| 9. Variability | Dominant emotion, % distribution, number of emotion changes, intensity variance | `ConversationAnalysisService.buildVariability` |
| 10. Raga | One overall raga (distribution-weighted), plus one raga per emotion share | `RagaSelectionService.selectRaga`, `ragasPerEmotion` |
| 11. Songs | Hindi-only retrieval → RRF → evidence → rerank → LLM selection → **emotion-share split** | `MusicRecommendationService.recommend(..., PlaylistPlan)`, `EmotionQuotaAllocator` |
| 12. Output | Transcript with roles, per-answer emotions (words / voice / used), distribution, ragas, Top-10 with explanations, warnings | `ConversationRecommendationResponse` |

If the user disagrees with the speaker labels, the UI's "Correct speaker labels" button calls `/reassign-roles`. Everything from step 7 on is recomputed without re-uploading the audio.

---

## 4. The pipeline, component by component

Each component: **what · why · how · code · in → out · limitations**.

### 4.1 Audio decoding
- **What / why:** turns any browser or phone format into raw samples, because the acoustic analysis and the voice model need PCM, not MP3/WebM.
- **How:** writes the upload to a temp file and runs `ffmpeg -y -i <input> -ac 1 -ar 16000 -f s16le <output>` through `ProcessBuilder`, then reads the raw samples back.
- **Code:** `audio/AudioTranscodingService.toPcm16Mono16k`.
- **In → out:** uploaded bytes → `short[]` at 16 kHz.
- **Limits:** needs ffmpeg installed (`FFMPEG_PATH`). Without it, acoustic analysis is skipped with a warning; transcription still works, because Whisper decodes on Groq's side.

### 4.2 Speech-to-text
- **What / why:** the words drive the text emotion; the timestamps let audio features be aligned with the text.
- **How:** Groq Whisper large-v3, temperature 0, `verbose_json` → segments (start, end, text).
- **Code:** `service/SpeechToTextService.transcribeWithSegments`; a fallback of a single whole-clip segment if the low-level API is unavailable.
- **Limits:** Whisper can hallucinate text over silence, so segments without voiced frames are dropped (`ConversationController.dropSegmentsWithoutSpeech`). Segment boundaries don't always match speaker changes: a segment containing both speakers gets one label.

### 4.3 Acoustic features and vocal arousal
- **What:** pitch and energy per 25 ms frame (10 ms hop); a rule-based **arousal** score (calm ↔ excited).
- **How:**
  - pitch by normalised autocorrelation in 80–400 Hz, with octave-error correction (tolerance 0.9);
  - energy = RMS;
  - a frame is voiced when energy and pitch are valid; speech needs ≥ 10 voiced frames.
  - Arousal = `0.5·energyNorm + 0.5·pitchVarNorm`, where `energyNorm = log10(meanEnergy+1)/4.5` and `pitchVarNorm = pitchStdDev(semitones)/8`. HIGH ≥ 0.66, MEDIUM ≥ 0.33, else LOW. Confidence = `0.7·voicedRatio + 0.3·min(1, frames/50)`.
- **Code:** `audio/AcousticFeatureExtractor`, `audio/AcousticEmotionAnalysisService`.
- **Use:** in voice mode, if arousal is HIGH (confidence ≥ 0.35) and text intensity < 0.6, intensity is averaged toward the arousal score (`MultimodalEmotionFusionService.fuse`). It never changes the emotion label.
- **Limits:** arousal ≠ emotion. It can't tell angry from excited. The thresholds are heuristics, not calibrated per speaker.

### 4.4 Speech emotion recognition (voice → emotion)
- **What / why:** the professor's requirement that in audio mode the emotion be detected from the **voice signal**, not only from transcribed words.
- **How:** `superb/wav2vec2-base-superb-er` (wav2vec 2.0 fine-tuned on IEMOCAP, the SUPERB benchmark), 4 classes: neutral, happy, angry, sad. It runs per segment in the local FastAPI service. Segments shorter than 500 ms get no prediction.
- **Code:** `ser-service/ser_service/app.py` (`/analyze`), `ser-service/ser_service/models.py` (`Wav2Vec2EmotionModel`); Java client `audio/SpeechEmotionClient`.
- **In → out:** base64 PCM + segment times → per segment `{label, confidence, probabilities}`.
- **Limits:** trained on **acted English** speech; only 4 classes. On the synthetic test audio it was mostly wrong (40% on 4 classes; see §5). Optional: if the service is down, the system falls back to words only and warns.

### 4.5 Fusing words and voice
- **What / why:** combines the two sources of evidence in a way that can be explained and tuned.
- **How** (`audio/SpeechEmotionFusion.fuse`):
  - `text(e)` = text confidence for the text label; the rest is spread evenly over the other 11 emotions.
  - `audio(e)` = voice probabilities mapped to canonical emotions: sad→SADNESS, happy→JOY, angry→ANGER, neutral→½ CALM + ½ RELAXATION.
  - **`fused(e) = (1 − w)·text(e) + w·audio(e)`**; the label is the arg-max (ties keep the text label).
  - `w` is set per request: `emotionSource=TEXT` → 0, `VOICE` → 1, `FUSED` → `app.ser.audio-weight` = **0.3**. In voice mode the text label is given confidence 0.6 (`app.ser.voice-text-confidence`), because the text analysis returns no confidence.
- **Why 0.3:** measured. On the audio set, fusion at 0.3 kept the words' 85.2%; at 0.5 accuracy fell to 63.0% (§5.3).
- **Output fields:** per answer `textEmotion`, `voiceEmotion`, `voiceEmotionConfidence`, `primaryEmotion` (used), `emotionSource`, `fusionNote`.

### 4.6 Speaker separation (diarization) and role identification
- **What / why:** the professor's point that "two people are involved, different audio pitch… we need to capture both individuals".
- **Voiceprint method** (`SpeakerDiarizationService.diarizeByEmbeddings`):
  - WavLM x-vector per segment; deterministic 2-means on cosine similarity, seeded by the least-similar pair.
  - **Reliable** only if the two cluster centroids have cosine similarity ≤ **0.86** and, with ≥ 4 segments, each cluster holds ≥ 2 segments.
  - Segments without a voiceprint inherit the nearest segment's speaker.
- **Pitch method** (`diarize`):
  - z-scored mean pitch plus 0.3 × z-scored energy per segment; 2-means seeded by the farthest pair.
  - Reliable only if the centroids are farther apart than the average within-cluster spread, the clusters' mean pitch differs by ≥ **1.2×** (~3 semitones), and the cluster-size rule holds.
- **Choice** (`choose`): reliable voiceprints → reliable pitch → otherwise not reliable (both explanations kept).
- **Roles** (`SpeakerRoleResolver.resolve`): the LLM labels each segment from text cues (who asks vs who describes their feelings, `labelRoles`). Each voice cluster takes the **majority label** of its segments; if both clusters vote the same role, the second is forced to the opposite. If separation isn't reliable, per-segment text labels are used and the response sets `speakerAssignmentUncertain = true`.
- **Limits:** on the test audio, male/female pairs separate (voiceprint similarity 0.53–0.66); the two same-gender **synthetic** voices were ~0.96 similar and are flagged uncertain. Real same-gender recordings haven't been tested.

### 4.7 Emotion analysis (LLM)
- **Overall:** `EmotionAnalysisService.analyze`, a JSON schema prompt over 12 canonical emotions. A malformed reply is repaired and retried once (`MAX_PARSE_ATTEMPTS = 2`). Labels are normalised (`reference/EmotionLabels`, e.g. NOSTALGY → NOSTALGIA). On LLM failure, a **keyword fallback** runs (English + Hinglish word lists) and is marked "Fallback" in `context_summary`.
- **Per answer:** `analyzeBatch`, **one** LLM call for all patient answers, each analysed independently, returning `{primary_emotion, intensity, confidence}`. Fallback segments get confidence 0.3 (`KEYWORD_FALLBACK_CONFIDENCE`), which the evaluation uses to flag them.
- **Limits:** depends on Groq availability. The free tier allows 8k tokens/minute and 200k/day, so quota exhaustion triggers the keyword fallback.

### 4.8 Emotional variability
`ConversationAnalysisService.buildVariability`:
- **Dominant emotion** = the most frequent per-answer emotion; ties go to the higher total intensity, then first seen.
- **Distribution** = % of answers per emotion, one decimal, summing to exactly 100 (largest-remainder rounding).
- **Transitions** = number of times the emotion changes between consecutive answers.
- **Intensity variance** across answers.
- **Distinct emotion count.**

### 4.9 Emotion → raga
- **Catalogue:** `reference/RagaRasaCatalog`, 22 ragas, each with a rasa, its traditionally associated emotions, time of day and description. Malkauns, Chandrakauns and Adana are flagged `cautionAtHighIntensity` (intensifying).
- **Overall raga** (`RagaSelectionService.selectRaga(emotion, distribution, hindiOnly)`):
  - weight per emotion = **distribution share** + **0.25** if it's the overall text emotion + **0.05** per related emotion;
  - each raga's score = sum of the weights of its emotions (`RagaRasaCatalog.coverage`);
  - if intensity ≥ **0.7** and the state is STRESS/ANXIETY/ANGER/FRUSTRATION, cautionary ragas are excluded;
  - the highest-scoring raga **with Hindi songs** is chosen; a substitution is reported in `warnings`;
  - the reason sentence states the coverage ("covers 75% of the patient's responses").
- **Raga per share** (`ragasPerEmotion`): for each emotion in the distribution, the first catalogue raga for that emotion with Hindi songs. Distress emotions avoid cautionary ragas unless nothing else exists. Emotions without such a raga are named in `warnings`.
- **Limits:** the raga–emotion associations are traditional/heuristic, not clinically validated. Todi, Marwa, Puriya Dhanashri, Shree and Hindol have no Hindi songs yet.

### 4.10 Retrieval, fusion, evidence, reranking, selection
`MusicRecommendationService.recommend(emotion, raga, hindiOnly, PlaylistPlan)`:

**Retrieve.** Independent ranked lists ("sources"), all filtered to `language = Hindi` **before** fusion:

| Source | Query | RRF weight |
|---|---|---|
| `raga_primary` | songs in the selected raga | 2.0 |
| `emotion_lang` | primary emotion + language | 1.5 |
| `raga_match` / `share_raga_*` | ragas linked to the emotions / each share's raga | 1.3 |
| `emotion_any`, `distribution_*` | primary emotion; each other emotion in the distribution | 1.2 |
| `secondary_*` | each secondary emotion (JSON contains) | 1.0 |
| `valence_energy` | valence/energy range for the emotion (`EvidenceGroundingService.valenceEnergyRange`) | 0.9 |
| `keyword_*` | top-3 mood keywords as mood tags | 0.8 |
| `semantic_dense` | TF-IDF cosine similarity between the expanded emotion profile and each song's text (`SemanticRetrievalService`) | 0.7 |

**Fuse (weighted Reciprocal Rank Fusion).** `score(song) = Σ_sources w(source) / (60 + rank)`, with k = 60 (Cormack et al., 2009). The top **30** go on.

**Evidence (propose → assign, pure code).** `EvidenceGroundingService` creates facts by comparing each song's real columns with the profile:

| Fact | Strength |
|---|---|
| primary emotion match | 1.0 |
| raga traditionally linked to the emotion | 0.9 |
| secondary emotion match | 0.7 |
| mood tag match | 0.6 |
| related emotion / related raga / valence-energy fit | 0.5 |
| language match | 0.4 |
| found by n ≥ 2 sources | 0.3 + 0.1n |

The **3 strongest** facts per song are kept.

**Lightweight rerank (code).** `LightweightRerankerService`: score = `0.5·(RRF/maxRRF) + 0.5·(evidence/maxEvidence)` → top **15**.

**Select (LLM).** One call: the LLM picks 10 and writes one sentence per song, constrained to that song's evidence list. If it fails or returns unusable JSON, the RRF order is used (`llmSelectionUsed = false`, warned). Songs without an LLM explanation get one assembled from their evidence (`ensureExplained`).

**Emotion-share split (conversation only).** `EmotionQuotaAllocator.allocate`:
- **Slots** by largest-remainder apportionment of 10 by share (always sums to 10).
- **Filled in passes**, each pass across all shares, so a strong match for one share isn't used up as a weak match for another:
  1. song's primary emotion = share emotion **and** song in the share's raga
  2. primary emotion
  3. secondary emotion
  4. song in the share's raga
- Unfilled slots go to the best remaining songs, with a warning. The output is ordered by share (dominant first); each song carries `matchedEmotion`; the split is returned as `emotionAllocation`.

**Limits:**
- The catalogue is small (76 Hindi tracks), so some shares can't be filled.
- TF-IDF tokenises `[a-z0-9]` only, so Devanagari text adds nothing to that source.
- Song mood annotations are the developer's; popularity is a stored field (50 for the cited additions).

### 4.11 Playlists beyond the current mood
`service/PlaylistService` + `components/PlaylistPanel.jsx`:
- **history:** songs recommended in this session, most frequent first;
- **by artist:** any credited singer;
- **by raga.**

All Hindi-only.

---

## 5. Evaluation: what's done, preliminary or planned

### 5.1 Status overview
| Item | Status | Where |
|---|---|---|
| Automated tests: 171 Java + 11 Python + 4 tooling, all passing | **Completed** (verifies the code does what's specified, not result quality) | `backend/src/test`, `ser-service/tests`, `evaluation/tools` |
| Audio evaluation on 7 synthetic recordings | **Completed, preliminary** (synthetic speech) | `evaluation/results/audio-eval-20261012-003802.md` |
| Live split test (mixed conversation): 13/13 checks | Completed (functional) | Run during development |
| Older e2e runs: 20/26 before earlier fixes; realistic 9/10; per-answer text emotion 32/34 | **Historical, preliminary.** From before this session's changes; the latest full 36-scenario e2e report is not in the repo (the file now holds a 2-scenario system check) | `e2e/results/` |
| Transcript evaluation, 18 conversations (emotion, raga, songs, NDCG, end-to-end) | **Implemented, not yet run successfully.** The Groq daily quota was exhausted on 2026-10-11/12 | `evaluation/dataset/conversations.json`, `EvaluationService` |
| Second annotator, expert raga/genre labels, human song grades | **Planned.** The sheets exist; nobody has filled them in yet | `evaluation/annotation/*.csv`, `evaluation/tools/annotation.py` |
| Real consented recordings | **Planned** | `evaluation/audio/README.md` |

### 5.2 Metric definitions (all in code)
| Metric | Definition | Code |
|---|---|---|
| Per-answer emotion accuracy | detected emotion = label, over all patient answers | `EvaluationService.evaluateCase` |
| Dominant-emotion accuracy | detected dominant = expected dominant | same |
| Distribution similarity | 1 − total variation distance between expected and detected % distributions | `EvaluationScoring.distributionSimilarity` |
| Raga Top-1 / Top-3 | chosen raga (or one of the next 2 alternates) ∈ acceptable set | `RagaMappingEvaluator.hitAtK` |
| Top-1 reachable | Top-1 only over cases where an acceptable raga has Hindi songs | `EvaluationService.aggregate` |
| Precision@10 | share of the Top-10 with relevance > 0 | `RankingMetrics.precisionAtK` |
| Recall@10 | relevant songs in Top-10 / all relevant Hindi songs | `RankingMetrics.recallAtK` |
| NDCG@10 | `DCG = Σ gain_i / log2(i+1)` over ranks i = 1…10, divided by the ideal DCG | `RankingMetrics.ndcgAtK` |
| Song emotion / raga / genre accuracy | share of the Top-10 whose primary emotion / raga / genre is acceptable (untagged raga = miss; genre only with labels) | `EvaluationScoring.songEmotionAccuracy`, `songRagaAccuracy`, `genreAccuracy` |
| Emotion-share fidelity | similarity between the expected distribution and the playlist's emotion mix | `EvaluationScoring.playlistComposition` |
| End-to-end accuracy | dominant emotion **and** Top-1 raga both correct | `EvaluationService.aggregate` |
| Speaker role accuracy | share of turns assigned to the right role, best cluster→role mapping | `AudioEvaluationScoring.roleAccuracy` |

**Ground truth, honestly:**
- Emotion labels hold by construction: each patient line was written to express its label, by the developer.
- Acceptable ragas come from the same rasa table the system uses, unless an expert supplies `expectedRagas`. This tests whether the pipeline *reaches* an appropriate raga, not whether the table is right.
- Song relevance is graded from metadata (primary emotion expected = 2, secondary = 1, acceptable raga +1) unless humans supply `songJudgments`.

### 5.3 Results you can show (synthetic audio set, 7 recordings, 27 patient answers)
| Speaker separation | Role accuracy | Reported reliable |
|---|---|---|
| Pitch / energy | 95.2% | 71.4% |
| Voiceprint | 93.2% | 71.4% |

| Emotion source | Accuracy |
|---|---|
| Words only (12 classes) | 85.2% |
| Voice only (4 classes, 15 scorable answers) | 40.0% |
| Fused, voice weight 0.3 / 0.5 / 1.0 | 85.2% / 63.0% / 11.1% |

**How to describe it:** "On synthetic speech, the voice model is out of domain and hurts accuracy above weight 0.3, so the system defaults to 0.3. Real recordings are the next step." Don't present these as real-world performance.

### 5.4 Baselines
There's no external baseline system. Internal comparisons that are already available or can be run:
- words vs voice vs fused (done);
- pitch vs voiceprint separation (done);
- split vs no split, compared through emotion-share fidelity;
- per-share ragas vs a single raga, compared through song raga accuracy.

Those last two need the transcript run. A popularity-only or emotion-only baseline isn't implemented.

---

## 6. Gaps against the professor's requirements

| Requirement | Status | Evidence |
|---|---|---|
| Analyse a Health Consultant–Patient conversation | **Fully implemented and verified (functionally)** | `ConversationController.analyzeConversation`; live run on `e2e/fixtures/real_indian_english_male_consultant_female_patient.mp3` |
| Focus on the patient, not the consultant | **Fully implemented and verified** | `ConversationPipelineService.run` filters `PATIENT` turns; `ConversationPipelineServiceTest` |
| Emotion per patient answer, track variation | **Fully implemented and verified** | `analyzeBatch`, `buildVariability`; `ConversationAnalysisServiceTest`; 85.2% words-only on the audio set |
| Distribution influences raga and playlist | **Fully implemented and verified** | `selectRaga(…, distribution, …)`, `ragasPerEmotion`, `EmotionQuotaAllocator`; `EmotionQuotaAllocatorTest`, `RagaPerEmotionSelectionTest`, `MusicRecommendationServiceEmotionSplitTest` |
| Audio-based emotion from the real signal | **Partially implemented.** The model runs on the voice and is fused, but it's only validated on synthetic speech, where it performs poorly, and it has 4 classes | `ser-service/`, `SpeechEmotionFusion`; `audio-eval-20261012-003802.md` |
| Relevant Top-10 Indian playlist | **Implemented; relevance not yet measured** | Hindi-only filter (`MusicRecommendationServiceHindiFilterTest`); the transcript evaluation is pending |
| Evaluate raga mapping and song quality (incl. NDCG) | **Partially implemented.** Framework complete and tested; results pending; human/expert labels missing | `EvaluationService`, `RankingMetrics`, `RagaMappingEvaluator` |
| Clear architecture and research evaluation | **Architecture done; evaluation partially done** | `docs/ARCHITECTURE.md`, `docs/PAPER_OUTLINE.md` |
| Real two-person conversations ("YouTube") | **Not implemented, by choice.** A consented recording protocol replaces it | `evaluation/audio/README.md` |
| Spotify-like playlist views | **Fully implemented** | `PlaylistService`, `PlaylistPanel.jsx`, `PlaylistServiceTest` |
| Genre-level accuracy | **Implemented; unverifiable without genre labels** | `EvaluationScoring.genreAccuracy` |

---

## 7. Presentation material

### 7.1 Two-minute introduction
> "MoodTunes recommends Hindi music from a person's emotional state, and our main focus is a recorded conversation between a health consultant and a patient. The system first transcribes the recording with Whisper and separates the two speakers, using voiceprints with pitch as a fallback, and it only trusts the split when the two voices are clearly different. It then looks only at the patient's answers and gives each answer its own emotion, from the words with an LLM and from the voice with a wav2vec2 speech-emotion model. From these answers we get an emotion distribution, for example fifty percent sad and twenty-five percent nostalgic, and we use it in two ways. First, to choose ragas from Hindustani rasa theory: one overall raga, and one raga for each emotion. Second, to build the Top-10 playlist proportionally, so a fifty-percent-sad patient gets five sad songs, not ten. Songs are retrieved from several sources, combined with reciprocal rank fusion, and the LLM's final selection may only cite facts that our code verified, so every explanation is grounded. For evaluation we have a transcript-level protocol with raga Top-1, Precision@10 and NDCG@10, and an audio-level protocol for speaker separation and voice emotion. The audio results so far are on synthetic speech, and the next step is real consented recordings."

### 7.2 Script for 10–15 minutes
1. **Problem (1 min).** Music therapy and raga-chikitsa; consultations reveal changing emotions; recommenders usually take one input and one label.
2. **Research questions (1 min).** RQ1–RQ4 from §1.
3. **Architecture (2 min).** Show `docs/ARCHITECTURE.md`: three modes into one pipeline, and the local speech-emotion service. Point out that the conversation mode is the main path.
4. **Conversation walkthrough (3 min).** Follow §3.3 with a real recording, using the UI: transcript with roles, per-answer badges (words vs voice), distribution bars, raga with coverage reason, playlist split chips ("SADNESS 50% → 5/5 songs · Raga Darbari Kanada").
5. **Key algorithms (3 min).**
   - fusion formula (§4.5);
   - reliability checks for speaker separation (§4.6);
   - distribution-weighted raga scoring (§4.9);
   - weighted RRF, evidence-grounded selection and the quota split (§4.10).
6. **Evaluation (2–3 min).** Metric table (§5.2); audio results (§5.3) with their limits; the transcript run is pending because of the quota.
7. **Limitations and next steps (1–2 min).** Real recordings, human labels, a larger raga-tagged library, a Hindi-trained voice model.

### 7.3 Likely technical questions
| Question | Answer grounded in the code |
|---|---|
| How do you separate the two speakers? | Voiceprints (WavLM x-vectors), cosine 2-means, trusted only if the two voices' similarity ≤ 0.86; otherwise pitch clustering with a 1.2× pitch-ratio rule; otherwise flagged uncertain. `SpeakerDiarizationService`. |
| How do you know which speaker is the patient? | An LLM labels each segment from conversational cues; each voice cluster takes its majority label. `SpeakerRoleResolver`. The user can correct it. |
| Is the emotion really from the audio? | Yes, in part: a wav2vec2 model classifies the voice into 4 classes and is fused with the words at weight 0.3 (or 1.0 with `emotionSource=VOICE`). Plus a rule-based arousal score from pitch and energy. |
| Why weight 0.3? | Measured: 0.5 dropped accuracy from 85.2% to 63.0% on the audio set. |
| How is the dominant emotion computed? | The most frequent per-answer emotion, ties by total intensity. `buildVariability`. |
| How do emotions become ragas? | A 22-raga rasa table; each raga scored by how much of the patient's emotion distribution it covers (+0.25 for the overall emotion); calming preference under acute distress; must have Hindi songs. |
| What is RRF and why use it? | Sums `w/(60+rank)` over independent ranked lists; robust to different score scales; weighted so precise sources count more. |
| How do you stop the LLM from inventing explanations? | It only gets each song's code-verified evidence and is instructed to cite only that; any song without an explanation gets one built from its evidence. |
| Where do the songs come from? | A curated catalogue in MySQL (121 tracks, Hindi-only output). 25 film songs have their raga from a cited source (`database/raga_tagged_hindi_songs.json`). |
| What happens if Groq is down? | Keyword fallback for emotion, RRF order for songs; both flagged in `warnings` and counted in the evaluation. |

### 7.4 Hard follow-up questions
| Question | Honest answer |
|---|---|
| What is novel here? | Not any single model: the combination. Patient-only, per-answer emotions from a two-speaker recording drive both raga choice and a proportional playlist, with grounded explanations and a reproducible evaluation. Claim it as a system contribution, not a new algorithm. |
| Isn't your raga ground truth circular? | Partly, and we say so. Without expert labels, acceptable ragas come from the same table, so it measures the pipeline reaching an appropriate raga, not the table's validity. The tool for expert labels is built (`annotation.py`). |
| Why are your emotion labels trustworthy? | Each line was written to express its label. A blind second annotator plus Cohen's kappa is planned; the sheet is ready. |
| Your voice model got 40%. Why include it? | It's the professor's requirement, and it's measured honestly. On synthetic speech it's out of domain; the fusion weight protects accuracy, and real recordings will tell us whether it helps. |
| Is NDCG meaningful with metadata relevance? | It's a proxy and generous by construction. Human song judgments replace it per case when provided. |
| Can it handle two women or two men? | Not shown yet: the synthetic same-gender voices are near-identical, so the system flags them as uncertain instead of guessing. |
| Is this clinical? | No. The analysis is explicitly non-diagnostic (`ConversationAnalysisDTO.disclaimer`); it suggests music only. |
| Why Hindi only? | It's a design requirement; enforced before fusion so no non-Hindi song can reach the LLM (tested). |

### 7.5 What to fix before presenting
1. **Run the transcript evaluation** when Groq has ~70k tokens free: `curl -X POST http://localhost:8080/api/v1/evaluation/run`. Check `casesWithLlmFallback = 0`.
2. **Re-run the full e2e suite** so a current full report exists (`python e2e/run_e2e.py --backend-log <log>`, ~150k tokens, on a different day from step 1).
3. **Record 10–20 consented role-plays** and label them (`evaluation/audio/manifest_template.json`).
4. **Second annotator and expert labels** (`python evaluation/tools/annotation.py export`).
5. **Be ready to show** the UI live: start MySQL, the backend, `ser-service` (`.venv/Scripts/python run.py`) and the frontend.
