"""Real models, loaded from the Hugging Face hub on first use and cached locally afterwards.

* superb/wav2vec2-base-superb-er — wav2vec 2.0 fine-tuned for speech emotion recognition on IEMOCAP
  (SUPERB benchmark, 4 classes). Trained on acted English speech: expect lower accuracy on Hindi and
  on spontaneous speech, which is why the backend fuses it with the text analysis instead of trusting it alone.
* microsoft/wavlm-base-plus-sv — WavLM fine-tuned for speaker verification (x-vectors).
"""
import numpy as np
import torch
from transformers import AutoFeatureExtractor, AutoModelForAudioClassification, WavLMForXVector

EMOTION_MODEL = "superb/wav2vec2-base-superb-er"
SPEAKER_MODEL = "microsoft/wavlm-base-plus-sv"

# The checkpoint's own short labels → readable names.
_LABELS = {"neu": "neutral", "hap": "happy", "ang": "angry", "sad": "sad"}


class Wav2Vec2EmotionModel:
    def __init__(self):
        self.extractor = AutoFeatureExtractor.from_pretrained(EMOTION_MODEL)
        self.model = AutoModelForAudioClassification.from_pretrained(EMOTION_MODEL).eval()
        id2label = self.model.config.id2label
        self.labels = [_LABELS.get(id2label[i], id2label[i]) for i in range(len(id2label))]

    @torch.no_grad()
    def predict(self, audio: np.ndarray, sample_rate: int) -> dict:
        inputs = self.extractor(audio, sampling_rate=sample_rate, return_tensors="pt")
        probs = torch.softmax(self.model(**inputs).logits[0], dim=-1).tolist()
        return dict(zip(self.labels, probs))


class WavLMSpeakerModel:
    def __init__(self):
        self.extractor = AutoFeatureExtractor.from_pretrained(SPEAKER_MODEL)
        self.model = WavLMForXVector.from_pretrained(SPEAKER_MODEL).eval()

    @torch.no_grad()
    def embed(self, audio: np.ndarray, sample_rate: int) -> np.ndarray:
        inputs = self.extractor(audio, sampling_rate=sample_rate, return_tensors="pt")
        emb = self.model(**inputs).embeddings[0]
        return torch.nn.functional.normalize(emb, dim=-1).numpy()
