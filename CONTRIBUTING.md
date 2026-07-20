# Contributing

## Development workflow

1. Create a focused branch from `main`.
2. Keep endpoint addresses and credentials outside source control.
3. Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` before opening a pull request.
4. Describe behavior changes, permission impact and validation evidence in the pull request.

## Engineering expectations

- Keep UI, state orchestration and data access responsibilities separated.
- Add tests for parsing, state transitions and security-data deduplication.
- Do not add screenshots, recordings or fraud samples without confirming their license and removing personal information.
- Any new Android permission must include a documented purpose, a user-consent flow and a graceful fallback.
- Production API traffic must use HTTPS; secrets and signing files must be stored in CI secrets or local secure storage.
