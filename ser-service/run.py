"""Start the speech-emotion service:  .venv/Scripts/python run.py   (default http://127.0.0.1:8090)

Loads both models at startup so the first request isn't slow; the first ever start downloads them
(~700 MB) into the Hugging Face cache.
"""
import argparse

import uvicorn

from ser_service.app import create_app
from ser_service.models import Wav2Vec2EmotionModel, WavLMSpeakerModel

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8090)
    a = p.parse_args()
    app = create_app(emotion_model=Wav2Vec2EmotionModel(), speaker_model=WavLMSpeakerModel())
    uvicorn.run(app, host=a.host, port=a.port)
