# Client API contract

This document describes the endpoints referenced by the Android client. It is not a substitute for a server-owned OpenAPI specification. Request and response schemas should be versioned and validated against the service before production release.

## Service groups

- **Core API** uses `BuildConfig.API_BASE_URL`.
- **AI API** uses `BuildConfig.AI_API_BASE_URL`.

Both URLs must end in `/`; the build configuration normalizes this automatically.

The code does not currently demonstrate a complete bearer-token or refresh-token flow. Deployments must add server-side authorization and a secure client session strategy rather than treating `userId` query/body values as proof of identity.

## Core API

### Authentication

| Method | Path | Client use |
| --- | --- | --- |
| `POST` | `auth/register` | Register an account and profile fields |
| `POST` | `auth/login` | Authenticate with account and password |

The registration request includes `account`, `password`, optional confirmation, username, age, gender and occupation. OTP verification is not part of the current server contract represented in this repository.

### User profile

| Method | Path | Client use |
| --- | --- | --- |
| `GET` | `user/profile?userId=...` | Load profile |
| `PUT` | `user/profile` | Update profile fields |
| `GET` | `user/occupations` | Load occupation options |
| `GET` | `user/security-score?userId=...` | Load a security score |

### Family members

| Method | Path | Client use |
| --- | --- | --- |
| `POST` | `family/member/add` | Add a family member |
| `GET` | `family/member/list?userId=...` | List family members and dashboard fields |

The client currently has no server contract for editing or deleting a family member; those actions must not be presented as persisted until matching endpoints exist.

### Interception and collection

| Method | Path | Client use |
| --- | --- | --- |
| `GET` | `intercept/weekly-dashboard?userId=...` | Aggregate weekly dashboard |
| `GET` | `intercept/history` | Paged/filterable interception history |
| `GET` | `intercept/call/weekly-stats?userId=...` | Call statistics |
| `GET` | `intercept/sms/weekly-stats?userId=...` | SMS statistics |
| `GET` | `intercept/suspicious-app/weekly-stats?userId=...` | Suspicious-app statistics |
| `GET` | `intercept/clipboard/weekly-stats?userId=...` | Clipboard statistics |
| `POST` | `intercept/ingest` | Ingest one device-risk item |
| `POST` | `intercept/ingest/batch` | Ingest a deduplicated batch |

Batch items carry a type, content/summary, source metadata and occurrence time. The server should enforce payload size, allowed types, ownership, idempotency and retention rules.

### Alert commands

| Method | Path | Client use |
| --- | --- | --- |
| `GET` | `api/alerts/commands/fraud-sms` | Poll a fraud-SMS alert command |
| `GET` | `api/alerts/commands/info-leak` | Poll an information-leak alert command |
| `GET` | `api/alerts/commands/ward-fraud` | Poll a protected-family-member alert command |

### Quiz score

| Method | Path | Client use |
| --- | --- | --- |
| `POST` | `quiz/score` | Submit a learning score |
| `GET` | `quiz/score?userId=...` | Load the latest score |

## AI API

| Method | Path | Transport | Client use |
| --- | --- | --- | --- |
| `POST` | `api/assistant/chat` | JSON | Non-streaming assistant message |
| `POST` | `api/assistant/chat/stream` | SSE / multipart | Streaming text and attachment chat |
| `POST` | `api/assistant/chat/stream/multimodal` | SSE / multipart | Streaming chat with multiple attachments |
| `POST` | `api/assistant/analyze` | JSON or multipart | Text, website and file risk analysis |
| `POST` | `api/assistant/check-sms` | JSON | Focused SMS classification |
| `POST` | `api/assistant/report/advice` | JSON | Generate report advice from risk context |

The stream parser recognizes three event families:

- `start`: session identifier, suggestions and safe actions
- `delta`: incremental assistant text
- `done`: final summary, probability, reasons and recommendations

The analysis response is expected to provide a risk label, fraud probability, confidence, fraud type/subtype, reasons, actions and optional knowledge-base hits. These fields are model outputs and should be validated, bounded and treated as advisory rather than authoritative.

## Error handling contract

Core endpoints commonly return an envelope shaped as:

```json
{
  "success": true,
  "message": "ok",
  "data": {}
}
```

For production consistency, services should additionally define:

- stable machine-readable error codes
- request and trace identifiers
- authentication failure semantics
- rate-limit headers and retry guidance
- multipart size and media-type limits
- SSE heartbeat, cancellation and terminal-error events
- idempotency behavior for ingest and registration

## Recommended server deliverable

The service repository should publish an OpenAPI document and contract tests. This client can then generate or validate DTOs instead of relying on manually synchronized Retrofit declarations.
