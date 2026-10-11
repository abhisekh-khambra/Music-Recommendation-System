# Speech-emotion service

A small local Python service the backend calls for two things it can't get from the words:

- **Emotion from the voice**: [`superb/wav2vec2-base-superb-er`](https://huggingface.co/superb/wav2vec2-base-superb-er),
  wav2vec 2.0 fine-tuned on IEMOCAP. It has 4 classes: neutral, happy, angry and sad.
- **Speaker voiceprints**: [`microsoft/wavlm-base-plus-sv`](https://huggingface.co/microsoft/wavlm-base-plus-sv)
  x-vectors. They let the backend tell two voices apart by timbre, not just pitch.

Everything runs on this machine, so patient audio is never sent to a third party. The service is optional. When
it isn't running, voice and conversation requests fall back to words + pitch analysis and say so in `warnings`.

## Setup (once)

```bash
cd ser-service
python -m venv .venv
.venv/Scripts/python -m pip install torch==2.8.0 --index-url https://download.pytorch.org/whl/cpu
.venv/Scripts/python -m pip install -r requirements.txt
```

## Run

```bash
.venv/Scripts/python run.py          # http://127.0.0.1:8090, first start downloads the models (~700 MB)
```

The backend finds it through `app.ser.url` (env `SER_URL`). Run `.venv/Scripts/python -m pytest tests` for the
tests; they use fake models, so they need no download.

## API

`POST /analyze` with `{"sampleRate": 16000, "pcmBase64": "<int16 LE mono>", "segments": [{"startMs", "endMs"}], "embeddings": true}`
returns an emotion and an optional voiceprint per segment, or `overall` for the whole clip when no segments are
given. Segments under 500 ms get no prediction.

## Known limits

- The emotion model was trained on **acted English** speech. On the project's synthetic test audio it was mostly
  wrong: voice-only accuracy was 40% on 4 classes, and it called most clips "angry". That's why the backend fuses it
  with the words at a low default weight (0.3) instead of trusting it alone. Real human recordings are needed
  to judge it fairly (see `evaluation/audio/README.md`).
- It knows only 4 emotions; NOSTALGIA, STRESS, ANXIETY and LOVE come from the words.
- Voiceprints separate a male/female pair clearly (similarity ~0.6). Two synthetic voices of the same gender
  were ~0.96 similar and are reported as uncertain, never split by guesswork.
