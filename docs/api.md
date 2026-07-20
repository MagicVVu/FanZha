# API reference and compatibility matrix

The backend publishes its generated OpenAPI document at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`. This document records the source-level contract and distinguishes it from Android-only expectations.

## Response envelope

Most implemented endpoints use:

```json
{
  "success": true,
  "message": "ok",
  "data": {}
}
```

Stable machine-readable error codes, trace IDs and token authentication are not implemented yet.

## Implemented backend endpoints

### Authentication

| Method | Path | Request | Notes |
| --- | --- | --- | --- |
| `POST` | `/auth/register` | `account`, `password`, optional `confirmPassword` | Account must be an email or mainland China mobile number |
| `POST` | `/auth/login` | `account`, `password` | Returns user identity; does not issue a token |

Unknown extra registration fields sent by the Android client are not persisted by this endpoint.

### AI chat

| Method | Path | Request | Notes |
| --- | --- | --- | --- |
| `POST` | `/ai/chat` | `prompt`, optional `system`, optional `model` | Non-streaming DeepSeek-compatible completion |

`DEEPSEEK_API_KEY` is required at runtime. This endpoint is not the Android SSE assistant contract.

### Ingestion (disabled by default)

Set `INGESTION_ENDPOINTS_ENABLED=true` to register these endpoints.

| Method | Path | Request | Notes |
| --- | --- | --- | --- |
| `POST` | `/upload` | multipart field `files` | Up to 200 files; parser support depends on format |
| `POST` | `/upload/text` | JSON field `content` | Normalizes encoding, masks sensitive content and returns a fingerprint summary |

The current ingestion response reports processing metadata; it is not the Android device-risk ingest API.

### Operations (disabled by default)

Set `ADMIN_ENDPOINTS_ENABLED=true` only behind a trusted control plane.

| Method | Path | Effect |
| --- | --- | --- |
| `POST` | `/admin/etl/knowledge/run` | Starts the configured knowledge ETL |
| `POST` | `/admin/crawler/run` | Starts one anti-fraud news crawl |

These operations currently start in-process daemon threads and do not expose durable job IDs.

### Platform endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/actuator/health` | Service/dependency health |
| `GET` | `/actuator/prometheus` | Prometheus metrics |
| `GET` | `/v3/api-docs` | Generated OpenAPI JSON |

## Android client contracts not implemented by this backend

| Contract group | Android paths | Backend status |
| --- | --- | --- |
| User profile | `user/profile`, `user/occupations`, `user/security-score` | Not implemented |
| Family | `family/member/add`, `family/member/list` | Not implemented |
| Interception | `intercept/...`, `intercept/ingest/batch` | Not implemented |
| Alert commands | `api/alerts/commands/...` | Not implemented |
| Quiz | `quiz/score` | Not implemented |
| Streaming assistant | `api/assistant/chat/stream...` | Not implemented |
| Multimodal analysis | `api/assistant/analyze`, `check-sms`, `report/advice` | Not implemented |

The Android SSE parser expects `start`, `delta` and `done` events with session, suggestion and risk metadata. `/ai/chat` returns one JSON response and cannot be substituted without an adapter.

## Security contract

The code does not demonstrate bearer-token or refresh-token handling. Until this exists:

- do not use a user ID as proof of ownership;
- keep admin and ingestion endpoints disabled or network-isolated;
- rate-limit registration, login and AI chat externally;
- bound multipart size and content types at both proxy and application layers;
- treat all model outputs as advisory and validate their structure.

## Recommended evolution

1. Introduce a versioned `/api/v1` surface and consistent error codes.
2. Add access/refresh tokens and per-resource authorization.
3. Implement an adapter matching the Android assistant DTOs and SSE events.
4. Add OpenAPI contract tests to both Gradle and Maven CI.
5. Add idempotency keys for ingestion and durable job resources for ETL.
