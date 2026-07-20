#!/usr/bin/env bash
set -euo pipefail

QDRANT_PATH="${KB_QDRANT_PATH:-/app/app/data/qdrant_store}"
PORT_VALUE="${PORT:-8000}"
WORKERS_VALUE="${UVICORN_WORKERS:-1}"

mkdir -p "${QDRANT_PATH}" /app/uploads /app/.cache

if [[ "${REBUILD_KB_ON_START:-false}" == "true" || ! -f "${QDRANT_PATH}/meta.json" ]]; then
  echo "[entrypoint] qdrant store missing or rebuild requested, rebuilding KB..."
  python -m app.scripts.rebuild_kb --tag "${KB_BUILD_TAG:-cloud_boot}"
fi

echo "[entrypoint] starting uvicorn on 0.0.0.0:${PORT_VALUE} workers=${WORKERS_VALUE}"
exec uvicorn app.main:app --host 0.0.0.0 --port "${PORT_VALUE}" --workers "${WORKERS_VALUE}" --proxy-headers
