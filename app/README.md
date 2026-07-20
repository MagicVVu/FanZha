# Android application module

`app` is the FanZha Android client. It contains the Compose UI, view models, local persistence, Retrofit contracts, multimodal request orchestration, local OCR and device-side risk collection.

Main packages:

- `data`: local persistence, API contracts, models and repositories
- `domain`: security-index calculation
- `security`: consent-aware SMS, call-log, clipboard and installed-app collection
- `notifications`: risk polling and local notification coordination
- `ui`: Compose screens, components, state holders and themes
- `util`: OCR, media and Android utility code

The server implementation is intentionally not represented in this module. See `../docs/api.md` for the client contract and `../docs/architecture.md` for system boundaries.
