# Deployment guide

## Supported components

This repository builds two deliverables:

- Android application: API 27+, built with JDK 17 and the Gradle wrapper.
- Spring Boot backend: Java 8 JAR or OCI image, backed by MySQL 8 and optionally Chroma.

## Backend with Docker Compose

Requirements: Docker Engine and Docker Compose v2.

```bash
git clone https://github.com/MagicVVu/FanZha.git
cd FanZha/backend
cp .env.example .env
```

Set unique local values for `DB_PASSWORD` and `MYSQL_ROOT_PASSWORD` in `.env`, then start:

```bash
docker compose up --build
```

The stack starts:

| Service | Default port | Persistence |
| --- | --- | --- |
| Backend | `8080` | Stateless container |
| MySQL | `3306` | `mysql-data` volume; initialized from `database/schema.sql` |
| Chroma | `8000` | `chroma-data` volume |

Verify:

```bash
curl http://localhost:8080/actuator/health
```

Do not enable `ADMIN_ENDPOINTS_ENABLED` or `INGESTION_ENDPOINTS_ENABLED` on an internet-facing instance until authentication and authorization are added.

## Backend without Docker

Requirements: JDK 8, Maven 3.8+ and MySQL 8.

1. Create a `fanzha` database and apply `database/schema.sql`.
2. Set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.
3. Run tests and package the service:

```bash
cd backend
./mvnw -B -ntp clean verify
java -jar target/fanzha-backend.jar
```

Optional integrations use `DEEPSEEK_API_KEY`, `CHROMA_*`, `EMBEDDING_*`, `PLAYWRIGHT_*` and crawler settings documented in `backend/README.md` and `application.yml`.

### Production backend checklist

1. Put the service behind TLS and an authenticated gateway.
2. Use a least-privilege MySQL account and managed secret store.
3. Keep Actuator exposure restricted to health and required metrics.
4. Add rate limits to login, chat and upload routes.
5. Keep crawler cookies, proxy credentials and browser profiles outside the image.
6. Pin and scan container images and Maven dependencies.
7. Back up MySQL/Chroma volumes and test restoration.
8. Introduce versioned database migrations before multi-instance rollout.

## Android configuration

Requirements: Android SDK 36, JDK 17 and an API 27+ device/emulator.

Copy the keys from `config/local.properties.example` into the ignored root `local.properties`:

```properties
api.base.url=http://10.0.2.2:8080/
ai.api.base.url=http://10.0.2.2:8080/
```

Environment alternatives are `FANZHA_API_BASE_URL`, `FANZHA_AI_API_BASE_URL` and the development-only `FANZHA_REGISTRATION_OTP`. Properties take precedence.

The local backend covers `auth/register` and `auth/login`, plus its own `/ai/chat` API. It does not yet implement the Android client's `/api/assistant/...` SSE/multimodal contract or the family/dashboard APIs.

Build on Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Build on macOS/Linux:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Release build

Keep Android signing material outside the repository and inject it through the release environment. Increment `versionCode`, enforce HTTPS endpoints and build:

```bash
./gradlew :app:bundleRelease
```

A public release also requires privacy-policy review for SMS, call logs, installed applications, notifications and background execution.

## Verification checklist

- Backend Maven tests and Android JVM tests pass.
- Docker Compose resolves without embedded credentials.
- `/actuator/health` is healthy after MySQL initialization.
- Registration/login work against a clean database.
- Optional AI and vector operations fail safely when credentials/services are absent.
- Feature-gated upload/admin endpoints are absent by default.
- Android permission denial leaves unrelated features usable.
- No endpoint credential, private dataset, SDK path or signing file appears in Git diff.

## Troubleshooting

- **Backend cannot validate schema:** apply `database/schema.sql` or recreate the empty Docker volume after confirming no required data exists.
- **Android emulator cannot reach backend:** use `10.0.2.2`, not `localhost`.
- **AI chat reports unavailable configuration:** provide `DEEPSEEK_API_KEY`; never place it in Git-tracked files.
- **Chroma startup is slow/unavailable:** leave `CHROMA_AUTO_INIT=false` unless vector features are being exercised.
- **Crawler returns no articles:** source anti-bot behavior changes; review network/legal constraints before enabling proxy, browser or CAPTCHA integrations.
