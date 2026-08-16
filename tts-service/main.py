"""
Prax TTS service — Kokoro-82M on CPU via ONNX.

Deliberately CPU-only: the RTX 3050 has 4GB VRAM and qwen2.5:7b already
overflows it. A second model on the GPU degrades both. 82M parameters on 8
cores still synthesises faster than real time, which is all sentence-level
streaming requires.

Binds 127.0.0.1 only. This service has no auth of its own — Spring is the only
client, and it must never be reachable from the network.

Run:
    pip install -r requirements.txt
    python main.py
"""

import io
import logging
import os
import struct
import time

import numpy as np
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

from voice_profile import PRAX_VOICE, SAMPLE_RATE

logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(levelname)-5s %(message)s")
log = logging.getLogger("prax-tts")

MODEL_DIR = os.environ.get("PRAX_TTS_MODEL_DIR", "./models")
MODEL_PATH = os.path.join(MODEL_DIR, "kokoro-v1.0.onnx")
VOICES_PATH = os.path.join(MODEL_DIR, "voices-v1.0.bin")

app = FastAPI(title="Prax TTS", version="1.0.0")

_engine = None
_load_error: str | None = None


def get_engine():
    """Lazy singleton. Loading is ~2s, so it happens once at first use."""
    global _engine, _load_error
    if _engine is not None or _load_error is not None:
        return _engine
    try:
        from kokoro_onnx import Kokoro

        t0 = time.perf_counter()
        _engine = Kokoro(MODEL_PATH, VOICES_PATH)
        log.info("Kokoro loaded in %.1fs (CPU)", time.perf_counter() - t0)
    except Exception as e:  # noqa: BLE001 - surfaced through /health
        _load_error = str(e)
        log.error("Kokoro failed to load: %s", e)
    return _engine


def to_wav(samples: np.ndarray, rate: int) -> bytes:
    """Minimal 16-bit PCM WAV writer — avoids a soundfile/libsndfile dependency."""
    pcm = np.clip(samples, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2").tobytes()

    buf = io.BytesIO()
    buf.write(b"RIFF")
    buf.write(struct.pack("<I", 36 + len(pcm)))
    buf.write(b"WAVEfmt ")
    buf.write(struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16))
    buf.write(b"data")
    buf.write(struct.pack("<I", len(pcm)))
    buf.write(pcm)
    return buf.getvalue()


class SpeakRequest(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    voice: str | None = None
    speed: float | None = Field(default=None, ge=0.5, le=1.5)


@app.get("/health")
def health():
    engine = get_engine()
    return {
        "ok": engine is not None,
        "model": os.path.basename(MODEL_PATH),
        "voice": PRAX_VOICE["voice"],
        "error": _load_error,
    }


@app.post("/tts")
def tts(req: SpeakRequest):
    engine = get_engine()
    if engine is None:
        raise HTTPException(status_code=503, detail=f"TTS engine unavailable: {_load_error}")

    voice = req.voice or PRAX_VOICE["voice"]
    speed = req.speed if req.speed is not None else PRAX_VOICE["speed"]

    t0 = time.perf_counter()
    try:
        samples, rate = engine.create(req.text, voice=voice, speed=speed, lang="en-us")
    except Exception as e:  # noqa: BLE001
        log.error("synthesis failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e)) from e

    samples = np.asarray(samples, dtype=np.float32)

    lead = PRAX_VOICE.get("lead_in_ms", 0)
    if lead:
        samples = np.concatenate([np.zeros(int(rate * lead / 1000), dtype=np.float32), samples])

    audio_s = len(samples) / rate
    elapsed = time.perf_counter() - t0
    # Realtime factor < 1.0 means synthesis outruns playback — the bar for streaming.
    log.info("%.2fs audio in %.2fs (rtf %.2f) · %d chars", audio_s, elapsed, elapsed / max(audio_s, 1e-6), len(req.text))

    return Response(
        content=to_wav(samples, rate),
        media_type="audio/wav",
        headers={
            "X-Prax-Audio-Seconds": f"{audio_s:.3f}",
            "X-Prax-Realtime-Factor": f"{elapsed / max(audio_s, 1e-6):.3f}",
        },
    )


if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("PRAX_TTS_PORT", "8087"))
    log.info("Prax TTS on http://127.0.0.1:%d  (model dir: %s)", port, MODEL_DIR)
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="warning")
