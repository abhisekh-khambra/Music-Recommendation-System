"""Speech-emotion / speaker-embedding service, tested with fake models (no download, no torch inference)."""
import base64

import numpy as np
import pytest
from fastapi.testclient import TestClient

from ser_service.app import create_app

SR = 16000


class FakeEmotionModel:
    """Says 'sad' for quiet audio and 'happy' for loud audio, so tests can tell segments apart."""
    labels = ["neutral", "happy", "angry", "sad"]

    def __init__(self):
        self.calls = []

    def predict(self, audio: np.ndarray, sample_rate: int) -> dict:
        self.calls.append(len(audio))
        loud = float(np.abs(audio).mean()) > 0.1
        return {"neutral": 0.1, "happy": 0.7 if loud else 0.1, "angry": 0.1, "sad": 0.1 if loud else 0.7}


class FakeSpeakerModel:
    def embed(self, audio: np.ndarray, sample_rate: int) -> np.ndarray:
        loud = float(np.abs(audio).mean()) > 0.1
        return np.array([1.0, 0.0, 0.0]) if loud else np.array([0.0, 1.0, 0.0])


def pcm(*parts):
    """parts: (seconds, amplitude) → base64 little-endian int16 mono PCM."""
    samples = np.concatenate([np.full(int(s * SR), a) for s, a in parts])
    return base64.b64encode((samples * 32767).astype("<i2").tobytes()).decode()


@pytest.fixture
def emotion():
    return FakeEmotionModel()


@pytest.fixture
def client(emotion):
    return TestClient(create_app(emotion_model=emotion, speaker_model=FakeSpeakerModel()))


def test_health_reports_models(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "UP"
    assert r.json()["emotionLabels"] == ["neutral", "happy", "angry", "sad"]


def test_each_segment_gets_its_own_emotion_and_embedding(client):
    body = {"sampleRate": SR, "pcmBase64": pcm((2, 0.02), (2, 0.5)),
            "segments": [{"startMs": 0, "endMs": 2000}, {"startMs": 2000, "endMs": 4000}], "embeddings": True}
    r = client.post("/analyze", json=body)
    assert r.status_code == 200
    segs = r.json()["segments"]
    assert [s["emotion"]["label"] for s in segs] == ["sad", "happy"]
    assert segs[0]["emotion"]["confidence"] == pytest.approx(0.7)
    assert set(segs[0]["emotion"]["probabilities"]) == {"neutral", "happy", "angry", "sad"}
    assert segs[0]["embedding"] != segs[1]["embedding"]


def test_whole_clip_is_analysed_when_no_segments_given(client):
    r = client.post("/analyze", json={"sampleRate": SR, "pcmBase64": pcm((3, 0.5))})
    assert r.status_code == 200
    assert r.json()["overall"]["label"] == "happy"
    assert r.json()["segments"] == []


def test_embeddings_are_omitted_unless_requested(client):
    r = client.post("/analyze", json={"sampleRate": SR, "pcmBase64": pcm((2, 0.5)),
                                      "segments": [{"startMs": 0, "endMs": 2000}]})
    assert r.json()["segments"][0]["embedding"] is None


def test_too_short_segment_has_no_prediction(client, emotion):
    # Under 500 ms there isn't enough speech for a reliable prediction — say so rather than guess.
    r = client.post("/analyze", json={"sampleRate": SR, "pcmBase64": pcm((2, 0.5)),
                                      "segments": [{"startMs": 0, "endMs": 300}], "embeddings": True})
    seg = r.json()["segments"][0]
    assert seg["emotion"] is None and seg["embedding"] is None


def test_segment_beyond_audio_is_clipped(client):
    r = client.post("/analyze", json={"sampleRate": SR, "pcmBase64": pcm((2, 0.5)),
                                      "segments": [{"startMs": 1000, "endMs": 9000}]})
    assert r.status_code == 200
    assert r.json()["segments"][0]["emotion"]["label"] == "happy"


@pytest.mark.parametrize("body", [
    {"sampleRate": SR, "pcmBase64": "not base64!!"},
    {"sampleRate": SR, "pcmBase64": ""},
    {"sampleRate": 0, "pcmBase64": pcm((1, 0.5))},
    {"sampleRate": SR, "pcmBase64": pcm((1, 0.5)), "segments": [{"startMs": 500, "endMs": 100}]},
])
def test_bad_input_is_rejected(client, body):
    assert client.post("/analyze", json=body).status_code in (400, 422)


def test_non_16k_audio_is_resampled_before_the_model(client, emotion):
    r = client.post("/analyze", json={"sampleRate": 8000, "pcmBase64": base64.b64encode(
        (np.full(8000 * 2, 0.5) * 32767).astype("<i2").tobytes()).decode()})
    assert r.status_code == 200
    assert emotion.calls[-1] == 2 * SR
