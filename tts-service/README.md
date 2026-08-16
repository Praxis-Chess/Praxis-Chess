# Prax TTS service

Local text-to-speech for Prax. Kokoro-82M (Apache-2.0) on CPU via ONNX.

Runs on `127.0.0.1:8087`. Spring is the only client — the browser never calls this
directly, and it must never be exposed to the network.

## Why CPU

The dev machine's RTX 3050 has 4GB VRAM and `qwen2.5:7b` already overflows it.
Putting a second model on the GPU makes both slower. At 82M parameters, Kokoro
synthesises faster than real time on 8 cores, which is all that sentence-level
streaming needs.

## Setup

```bash
cd tts-service
python -m venv .venv
.venv\Scripts\activate        # Windows
pip install -r requirements.txt
```

Download the two model files into `./models/`:

- `kokoro-v1.0.onnx`
- `voices-v1.0.bin`

Both are published on the [kokoro-onnx releases page](https://github.com/thewh1teagle/kokoro-onnx/releases).
Set `PRAX_TTS_MODEL_DIR` if you keep them elsewhere.

## Run

```bash
python main.py
```

## Verify

```bash
curl http://127.0.0.1:8087/health
```

```bash
curl -X POST http://127.0.0.1:8087/tts -H "Content-Type: application/json" -d "{\"text\":\"There is a pattern here.\"}" --output test.wav
```

The response carries `X-Prax-Realtime-Factor`. Anything **below 1.0** means synthesis
outruns playback — the bar for streaming. Above 1.0 and you should switch to Piper.

## If Praxis can't reach it

Nothing breaks. The frontend binds `MockVoice`, the listen control never renders,
and Prax stays silent but fully functional.
