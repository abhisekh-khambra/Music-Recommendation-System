# Paper outline (draft for Friday's discussion)

**Working title:** Raga-Based Music Recommendation from Health-Consultant–Patient Conversations: Emotional Variability, Speech Emotion and Raga Mapping

Each section lists what it should say and where the material already exists in the project. Numbers marked **[run]** come from an evaluation run that hasn't completed yet (see §6.4).

---

## Abstract (≈200 words, write last)
- Problem: a therapist's recorded conversation reveals a patient's changing emotional state. Can it drive an appropriate, explainable music recommendation grounded in Hindustani raga theory?
- Approach: a three-mode system (text, voice, conversation). In conversation mode the two speakers are separated, each patient answer gets its own emotion from the words and the voice, an emotion distribution is built, one raga is chosen per emotion, and a Top-10 Hindi playlist is split across the emotions by share.
- Evaluation: transcript-level (emotion, raga Top-1/Top-3, Precision/Recall/NDCG@10, song-level raga and emotion accuracy, end-to-end accuracy) and audio-level (speaker role accuracy, words vs voice vs fused emotion).
- Key results: **[run]**; on the audio side, words alone 85.2% vs voice alone 40% (4-class), with fusion at weight 0.3 keeping 85.2%.

## 1. Introduction
- Music therapy and raga-chikitsa: ragas are traditionally linked to rasas (emotional essences).
- Gap: emotion-aware recommenders take one input and one emotion. A consultation is a two-person dialogue whose emotions vary from answer to answer.
- Contributions:
  1. A conversation pipeline that separates speakers and analyses only the patient's answers.
  2. Emotional variability as a distribution over answers, which drives both the raga choice and the playlist split ("50% sad → 5 of 10 songs").
  3. One raga per emotion share, from a documented rasa table, restricted to ragas with Hindi songs.
  4. Words + voice emotion fusion with a selectable source (TEXT / VOICE / FUSED).
  5. A two-part evaluation protocol with released labels and code.

## 2. Related work
- Emotion-aware / context-aware music recommendation.
- Raga–rasa theory and raga-based music therapy (cite the sources behind `RagaRasaCatalog`).
- Speech emotion recognition: wav2vec 2.0 / SUPERB-ER (IEMOCAP, 4 classes).
- Speaker diarization: x-vectors / WavLM speaker verification, versus pitch-based clustering.
- Ranking metrics: Precision@K, NDCG (Järvelin & Kekäläinen 2002); reciprocal rank fusion (Cormack et al. 2009).

## 3. System
*Figure 1: block diagram, `docs/ARCHITECTURE.md` (three modes → shared pipeline → evaluation).*
- 3.1 Input modes: text; voice (single speaker); conversation (Health Consultant + Patient), the main focus.
- 3.2 Speech processing: Whisper segments; voiceprint diarization with a pitch fallback and a reliability check; role labelling.
- 3.3 Emotion: per-answer text emotion (LLM, 12 labels); voice emotion (wav2vec2, 4 classes); late fusion `(1−w)·text + w·voice`; emotional-variability statistics.
- 3.4 Raga selection: distribution-weighted coverage over the rasa table; calming preference for acute distress; one raga per emotion share.
- 3.5 Recommendation: multi-source retrieval → weighted RRF → evidence grounding → LLM selection with evidence-only explanations → emotion-share split (largest-remainder quotas; passes: emotion + raga, emotion, secondary emotion, raga).
- 3.6 Playlist views: current mood, session history, artist, raga.
- 3.7 Song library: 121 tracks (76 Hindi). 25 Hindi film songs have a raga from a cited source; 17 of 22 ragas are reachable.

## 4. Datasets
- 4.1 Transcript set: 18 scripted consultations (English, Hinglish, Devanagari Hindi; single-emotion, mixed and high-variability). Each patient answer is written to express a labelled emotion. Second-annotator labels and Cohen's κ come via `evaluation/tools/annotation.py`.
- 4.2 Audio set: 7 synthetic two-speaker recordings (27 patient answers) with turn times and emotions, plus **real consented role-play recordings (to collect)**, protocol in `evaluation/audio/README.md`.
- 4.3 Ground truth: emotion labels by construction; raga labels from the rasa table unless an expert supplies them; song relevance graded from metadata unless human judgments are supplied.

## 5. Evaluation protocol
*Table 1: metric definitions (README "Evaluation" section).*
- Emotion level: per-answer accuracy, dominant-emotion accuracy, distribution similarity (1 − TVD).
- Raga level: Top-1, Top-3, Top-1 on reachable cases, catalogue agreement.
- Song level (Top 10): Precision@10, Recall@10, NDCG@10, song emotion accuracy, song raga accuracy, genre accuracy (with labels), emotion-share fidelity.
- Overall: end-to-end accuracy (dominant emotion and Top-1 raga both correct).
- Audio: speaker role accuracy (pitch vs voiceprint); words vs voice vs fused at w ∈ {0, 0.3, 0.5, 1}.

## 6. Results
- 6.1 Transcript evaluation, *Table 2* **[run]**: summary by level, breakdown by dominant emotion.
- 6.2 Audio evaluation, *Table 3* (synthetic set, run 2026-10-12):

  | Speaker separation | Role accuracy | Reported reliable |
  |---|---|---|
  | Pitch / energy | 95.2% | 71.4% |
  | Voiceprint | 93.2% | 71.4% |

  | Emotion source | Accuracy |
  |---|---|
  | Words only (12 classes) | 85.2% |
  | Voice only (4 classes, 15 scorable answers) | 40.0% |
  | Fused w = 0.3 / 0.5 / 1.0 | 85.2% / 63.0% / 11.1% |

- 6.3 Ablation: playlist with vs without the emotion-share split (share fidelity); with vs without per-share ragas (song raga accuracy) **[run]**.
- 6.4 Status: the full transcript run needs about 70k LLM tokens of fresh daily quota (Groq free tier: 200k/day).

## 7. Discussion and limitations
- Synthetic speech: the voice model is out of domain (acted English training data). Real recordings are needed before claiming voice-emotion results.
- Same-gender speakers: flagged uncertain rather than split. Voiceprints still need testing on real voices.
- Ground truth: developer-written emotion labels and catalogue-derived raga labels until second-annotator and expert labels are collected.
- Library: 5 ragas still have no Hindi songs. Mood annotations of songs are the developer's.
- Non-diagnostic by design: the system gives mood-based music suggestions, not clinical assessments.

## 8. Ethics
Consent for every recording; real recordings kept out of the repository; audio processed locally; the explanation for every song is traceable to catalogue facts.

## 9. Conclusion and future work
Real consultation recordings; expert raga labels; a larger raga-tagged Hindi library; a speech emotion model fine-tuned on Hindi speech; listener studies of the recommendations.

---

**Before Friday:** (1) complete the transcript evaluation run; (2) get a second annotator for `evaluation/annotation/emotion_labels.csv`; (3) record 10–20 consented role-plays.
