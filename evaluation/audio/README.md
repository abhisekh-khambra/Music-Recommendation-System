# Audio evaluation recordings

The audio evaluation (`POST /api/v1/evaluation/run-audio`) measures the parts of the conversation pipeline
that only exist for audio:

- **Speaker separation**: is each turn assigned to the right speaker (Health Consultant or Patient)? It compares
  pitch clustering with voiceprint clustering.
- **Emotion from the voice**: the speech-emotion model on each patient response, compared with the emotion
  from the words and with the two fused at several voice weights.

`manifest_synthetic.json` uses the computer-generated (text-to-speech) test conversations from `e2e/fixtures`,
rebuilt by `python evaluation/tools/build_audio_manifest.py`. **Synthetic speech only checks that the pipeline
works.** On it the voice model is mostly wrong (it was trained on human actors), and same-gender TTS voices
are almost identical. Results for a paper need real human recordings.

## Getting real recordings

The professor suggested taking the audio from a YouTube discussion. Don't download from YouTube: it breaks
YouTube's terms, the audio is usually copyrighted, and a real health conversation contains someone's private
information. Use one of these instead:

1. **Record a role-play** (recommended). Two people, with both agreeing to the recording, act out a consultation
   using the questions from the meeting ("How are you feeling today?", "How has your sleep been?", "Have you been
   worried about anything?", "What do you do in your free time?"). The patient answers each question with an
   emotion chosen in advance, so the labels are known. Record 10–20 conversations. Include two same-gender pairs
   to test speaker separation, and a few in Hindi.
2. **A consented research dataset** with two-person emotional conversations and per-utterance labels, for
   example IEMOCAP (USC, free for research on request). Convert its labels to the format below.

Use a phone or laptop microphone in a quiet room. Any format ffmpeg reads works (WAV, MP3, M4A, WebM).

## Label format

Copy `manifest_template.json` to `manifest_real.json`, put the audio files next to it, and fill in one entry per
recording. Each turn needs who spoke, when (milliseconds, any audio editor shows this), what was said, and,
for patient turns, the emotion it expresses. Use one of: JOY, SADNESS, STRESS, ANGER, RELAXATION, ANXIETY,
EXCITEMENT, MELANCHOLY, NOSTALGIA, LOVE, FRUSTRATION, CALM.

Then run:

```bash
curl -X POST "http://localhost:8080/api/v1/evaluation/run-audio?manifest=../evaluation/audio/manifest_real.json"
```

`manifest_real.json` and the audio files contain people's voices. Keep them out of git unless everyone recorded
has agreed to that (`evaluation/audio/real/` is gitignored for this).
