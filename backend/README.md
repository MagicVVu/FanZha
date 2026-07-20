# FanZha Backend

FanZha Backend is the Spring Boot service included in the FanZha monorepo. It provides account registration and login, non-streaming LLM chat, document ingestion, anti-fraud news ETL, MySQL persistence and Chroma vector indexing.

## Capability boundary

| Capability | Status |
| --- | --- |
| Email/mobile registration and login | Implemented; BCrypt password hashes, no token issuance yet |
| DeepSeek-compatible chat | Implemented at `POST /ai/chat`; requires an API key |
| Text and file ingestion | Implemented; disabled by default |
| Scheduled knowledge ETL | Implemented; disabled by default |
| Anti-fraud news crawler and extraction | Implemented; disabled by default |
| Chroma indexing | Implemented and optional |
| Milvus adapter | Present as an experimental adapter, not wired into the active ETL path |
| Android SSE/multimodal assistant contract | Not implemented by this service |

The service does not currently issue JWTs or provide role-based authorization. Keep admin and ingestion endpoints disabled on public deployments until an authentication layer is added.

## Requirements

- JDK 8
- Maven 3.8+
- MySQL 8
- Optional: Chroma, a DeepSeek-compatible API, BGE embedding service, Playwright and Tesseract

## Local run

Initialize MySQL with `../database/schema.sql`, then set at least:

```bash
export DB_URL='jdbc:mysql://localhost:3306/fanzha?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USERNAME='fanzha'
export DB_PASSWORD='your-local-password'
./mvnw spring-boot:run
```

Windows PowerShell uses `$env:DB_URL`, `$env:DB_USERNAME` and `$env:DB_PASSWORD` for the same values.

Health and OpenAPI endpoints:

- `GET /actuator/health`
- `GET /swagger-ui.html`
- `GET /v3/api-docs`

Run the test suite:

```bash
./mvnw -B -ntp verify
```

## Docker Compose

```bash
cp .env.example .env
# Replace both database passwords in .env
docker compose up --build
```

The Compose stack starts MySQL, Chroma and the backend. External AI, embedding, crawling, ingestion and admin operations remain off unless explicitly enabled.

## Important settings

| Environment variable | Purpose | Default |
| --- | --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MySQL connection | Local MySQL / empty password |
| `DEEPSEEK_API_KEY` | Enables calls made through `/ai/chat` | Empty |
| `CHROMA_AUTO_INIT` | Create/check a collection during startup | `false` |
| `KNOWLEDGE_ETL_ENABLED` | Enable scheduled legacy knowledge ETL | `false` |
| `CRAWLER_ENABLED` | Enable scheduled news crawler | `false` |
| `DEEPSEEK_EXTRACT_ENABLED` | Enable LLM extraction inside the crawler | `false` |
| `INGESTION_ENDPOINTS_ENABLED` | Register `/upload` endpoints | `false` |
| `ADMIN_ENDPOINTS_ENABLED` | Register manual ETL/crawler endpoints | `false` |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | Comma-separated browser origins | Localhost only |

The complete configuration is in `src/main/resources/application.yml`. Do not commit a populated `.env`, crawler cookies, Playwright profiles, datasets or vector database files.

## Source layout

```text
backend/
├── src/main/java/com/magicvvu/fanzha/backend/
│   ├── config/       # Typed configuration and infrastructure beans
│   ├── controller/   # HTTP endpoints
│   ├── dao/          # Spring Data repositories
│   ├── entity/       # JPA entities
│   ├── service/      # Auth, ingestion, crawler and vector workflows
│   └── util/         # External clients, parsing and masking utilities
├── src/test/         # Unit tests for ETL filtering, retry and vector math
├── scripts/          # Chroma diagnostics/maintenance helpers
├── Dockerfile
└── docker-compose.yml
```
