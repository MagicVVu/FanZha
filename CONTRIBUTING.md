# Contributing

## Development workflow

1. Create a focused branch from `main`.
2. Keep endpoint addresses and credentials outside source control.
3. Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` for Android changes.
4. Run `cd backend && ./mvnw -B -ntp verify` for backend changes.
5. Describe behavior changes, permission/security impact and validation evidence in the pull request.

## Engineering expectations

- Keep UI, state orchestration and data access responsibilities separated.
- Add tests for parsing, state transitions and security-data deduplication.
- Do not add screenshots, recordings or fraud samples without confirming their license and removing personal information.
- Any new Android permission must include a documented purpose, a user-consent flow and a graceful fallback.
- Backend endpoints must validate input, avoid serializing persistence entities directly and document authorization requirements.
- Crawler datasets, cookies, browser profiles and vector-store state must remain outside source control.
- Production API traffic must use HTTPS; secrets and signing files must be stored in CI secrets or local secure storage.
