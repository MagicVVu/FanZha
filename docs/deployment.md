# Deployment guide

## Supported target

This repository builds an Android application. It does not deploy the external core API, AI service, database or vector store.

## Requirements

- Android Studio with Android SDK Platform 36 and Build Tools
- JDK 17+
- Android 8.1 / API 27 or newer device or emulator
- Network access to Google Maven, Maven Central and JitPack for a clean dependency restore

## Local configuration

Android Studio creates `local.properties` with `sdk.dir`. Add the application settings below using `config/local.properties.example` as the source:

```properties
api.base.url=https://api.example.com/
ai.api.base.url=https://ai.example.com/
```

The endpoints must include a scheme. A trailing slash is added by the build script when absent. If no URL is supplied, the debug build targets `http://10.0.2.2:8080/`, which maps an Android emulator to the development host.

Environment variable alternatives:

| Variable | Purpose |
| --- | --- |
| `FANZHA_API_BASE_URL` | Core business service |
| `FANZHA_AI_API_BASE_URL` | AI and streaming service |
| `FANZHA_REGISTRATION_OTP` | Local integration only; never a production OTP mechanism |

Properties in `local.properties` take precedence over environment variables.

## Build

Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

macOS or Linux:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Expected output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install to a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release build

Create and retain a release keystore outside the repository. Never commit `.jks`, `.keystore` or `keystore.properties` files. Configure signing through Android Studio or CI secrets, increment `versionCode`, and build an App Bundle:

```bash
./gradlew :app:bundleRelease
```

The repository intentionally contains no signing identity. A production release should also enforce HTTPS endpoints, review logging, publish a privacy policy and validate all sensitive permission flows.

## Verification checklist

1. Gradle sync and unit tests complete successfully.
2. The app opens on API 27 and the current target API.
3. Login and profile calls reach the core service.
4. Text analysis and SSE assistant calls reach the AI service.
5. Denying SMS or call-log permissions leaves unrelated features usable.
6. Consent is requested before device data collection starts.
7. Notification behavior is tested on Android 13+.
8. No endpoint, credential, SDK path or signing file appears in the Git diff.

## Troubleshooting

- **Gradle cannot resolve dependencies:** verify access to `services.gradle.org`, Google Maven, Maven Central and JitPack.
- **Emulator cannot reach a local service:** bind the service to an accessible interface and use `10.0.2.2`, not `localhost`.
- **Retrofit rejects the base URL:** include `http://` or `https://`; the build adds the trailing slash.
- **HTTP traffic is blocked:** cleartext is derived from configured URLs. Use HTTPS for production; use HTTP only for controlled development.
- **SMS/call features are unavailable:** confirm device capability, runtime permissions and platform policy restrictions.
- **SSE never completes:** verify that the proxy and server do not buffer event streams and that the AI endpoint emits valid SSE frames.
