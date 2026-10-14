# The one-shot "models" service: pulls the models Praxis is configured for and
# builds the trained commentary model from its Hugging Face release, through
# Ollama's HTTP API, then exits. Standard-library Python only; the base is
# shared with the voice image.
FROM python:3.12-slim
COPY docker/models/pull_models.py /usr/local/bin/pull_models.py
RUN useradd --system --uid 10003 models && mkdir -p /setup && chown models /setup
USER models
ENTRYPOINT ["python", "/usr/local/bin/pull_models.py"]
