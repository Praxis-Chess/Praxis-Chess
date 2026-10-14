# syntax=docker/dockerfile:1.7
# Prax's voice: Kokoro-82M on CPU (ONNX). Used by docker/compose.yaml under the
# "voice" profile. Build context: this folder.
FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# The two model files from the kokoro-onnx release (model-files-v1.0). The
# release publishes no checksums: voices is pinned to the SHA-256 of the copy
# this project has used since August; the model is checked by exact size.
ARG KOKORO=https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0
ADD --checksum=sha256:bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d \
    ${KOKORO}/voices-v1.0.bin /app/models/voices-v1.0.bin
ADD ${KOKORO}/kokoro-v1.0.onnx /app/models/kokoro-v1.0.onnx
RUN test "$(stat -c %s /app/models/kokoro-v1.0.onnx)" = 325532387 \
 || { echo "kokoro-v1.0.onnx is not the expected 325,532,387 bytes" >&2; exit 1; }

COPY main.py voice_profile.py ./
RUN useradd --system --uid 10002 prax && chmod -R a+rX /app
USER prax

ENV PRAX_TTS_MODEL_DIR=/app/models \
    PRAX_TTS_HOST=0.0.0.0 \
    PRAX_TTS_PORT=8087
EXPOSE 8087
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8087/health', timeout=4).status == 200 else 1)"
CMD ["python", "main.py"]
