FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PORT=8000 \
    UVICORN_WORKERS=1 \
    KB_QDRANT_PATH=/app/app/data/qdrant_store \
    HF_HOME=/app/.cache/huggingface \
    TRANSFORMERS_CACHE=/app/.cache/huggingface \
    XDG_CACHE_HOME=/app/.cache \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /app/requirements.txt

RUN pip install --upgrade pip setuptools wheel \
    && pip install -r /app/requirements.txt \
    && python -m playwright install --with-deps chromium

COPY . /app

RUN chmod +x /app/deploy/entrypoint.sh \
    && mkdir -p /app/uploads /app/app/data /app/.cache

EXPOSE 8000

ENTRYPOINT ["/app/deploy/entrypoint.sh"]
