"""Local speech-emotion recognition (SER) and speaker-embedding service.

The Java backend already decodes uploads to 16 kHz mono PCM with ffmpeg, so this service takes raw
int16 PCM (base64) plus speech-segment time ranges and returns, per segment:
  * emotion  — a 4-class prediction from the VOICE itself (neutral / happy / angry / sad), not the words
  * embedding — a speaker voiceprint, so the backend can tell two same-gender voices apart

Runs entirely on this machine: patient audio is never sent to a third party.
"""
import base64
import binascii
from typing import List, Optional

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

TARGET_SR = 16000
MIN_SEGMENT_MS = 500  # shorter clips don't carry enough speech for a reliable prediction


class Segment(BaseModel):
    startMs: int = Field(ge=0)
    endMs: int = Field(ge=0)


class AnalyzeRequest(BaseModel):
    sampleRate: int = Field(gt=0)
    pcmBase64: str
    segments: List[Segment] = []
    embeddings: bool = False


def decode_pcm(pcm_base64: str) -> np.ndarray:
    try:
        raw = base64.b64decode(pcm_base64, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=400, detail="pcmBase64 is not valid base64")
    if len(raw) < 2:
        raise HTTPException(status_code=400, detail="no audio samples")
    return np.frombuffer(raw[: len(raw) // 2 * 2], dtype="<i2").astype(np.float32) / 32768.0


def resample(audio: np.ndarray, sr: int) -> np.ndarray:
    """Linear-interpolation resampling to 16 kHz — adequate for speech models, and dependency-free."""
    if sr == TARGET_SR:
        return audio
    n = int(round(len(audio) * TARGET_SR / sr))
    return np.interp(np.linspace(0, len(audio) - 1, n), np.arange(len(audio)), audio).astype(np.float32)


def emotion_result(probabilities: dict) -> dict:
    label = max(probabilities, key=probabilities.get)
    return {"label": label, "confidence": round(float(probabilities[label]), 4),
            "probabilities": {k: round(float(v), 4) for k, v in probabilities.items()}}


def create_app(emotion_model=None, speaker_model=None) -> FastAPI:
    """Models are injected so tests can use fakes; when omitted, the real models load lazily on first use."""
    app = FastAPI(title="Speech emotion service")
    state = {"emotion": emotion_model, "speaker": speaker_model}

    def emotion():
        if state["emotion"] is None:
            from ser_service.models import Wav2Vec2EmotionModel
            state["emotion"] = Wav2Vec2EmotionModel()
        return state["emotion"]

    def speaker():
        if state["speaker"] is None:
            from ser_service.models import WavLMSpeakerModel
            state["speaker"] = WavLMSpeakerModel()
        return state["speaker"]

    @app.get("/health")
    def health():
        return {"status": "UP", "emotionLabels": list(emotion().labels)}

    @app.post("/analyze")
    def analyze(req: AnalyzeRequest):
        for s in req.segments:
            if s.endMs <= s.startMs:
                raise HTTPException(status_code=400, detail=f"segment {s.startMs}-{s.endMs} ms has no duration")
        audio = resample(decode_pcm(req.pcmBase64), req.sampleRate)

        def clip(start_ms: int, end_ms: int) -> Optional[np.ndarray]:
            a, b = start_ms * TARGET_SR // 1000, min(len(audio), end_ms * TARGET_SR // 1000)
            if b - a < MIN_SEGMENT_MS * TARGET_SR // 1000:
                return None
            return audio[a:b]

        segments = []
        for s in req.segments:
            part = clip(s.startMs, s.endMs)
            segments.append({
                "startMs": s.startMs, "endMs": s.endMs,
                "emotion": None if part is None else emotion_result(emotion().predict(part, TARGET_SR)),
                "embedding": None if part is None or not req.embeddings
                else [round(float(x), 6) for x in speaker().embed(part, TARGET_SR)],
            })

        overall = None
        if not req.segments and len(audio) >= MIN_SEGMENT_MS * TARGET_SR // 1000:
            overall = emotion_result(emotion().predict(audio, TARGET_SR))
        return {"overall": overall, "segments": segments}

    return app
