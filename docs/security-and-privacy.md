# Security and privacy

FanZha processes data that may reveal communications, contacts and installed applications. A successful build is not sufficient evidence that the app is ready for public distribution.

## Sensitive permissions

| Permission | Client purpose | Required safeguard |
| --- | --- | --- |
| `READ_SMS` / `RECEIVE_SMS` | Detect and ingest suspicious SMS | Explicit consent, data minimization and graceful denial |
| `READ_CALL_LOG` | Identify suspicious call activity | Purpose limitation and retention controls |
| `QUERY_ALL_PACKAGES` | Detect potentially suspicious apps | Store-policy justification and narrow alternatives |
| `POST_NOTIFICATIONS` | Deliver risk alerts | Runtime request on Android 13+ |
| `SCHEDULE_EXACT_ALARM` | Schedule risk polling | Battery/policy review and push-based replacement plan |
| `RECEIVE_BOOT_COMPLETED` | Restore scheduled checks | Clear user control to disable monitoring |

## Existing client safeguards

- Collection starts behind an in-app consent gate.
- Runtime permission state is captured and unavailable sources are skipped.
- SMS and call-log ingestion uses timestamp watermarks.
- Clipboard data is skipped when unchanged.
- Installed-app uploads are deduplicated and periodically refreshed.
- Local configuration, signing material and common secret files are ignored by Git.
- HTTP logging is disabled in release builds.

## Production requirements

- Publish a privacy policy describing each field, purpose, recipient, retention period and deletion route.
- Authenticate and authorize every API request; the current client contracts do not demonstrate a complete token strategy.
- Use TLS, certificate monitoring and server-side request validation.
- Encrypt sensitive local data or replace SharedPreferences with an encrypted store where appropriate.
- Avoid sending complete message bodies when a derived risk feature is sufficient.
- Provide consent withdrawal, data export and deletion flows.
- Review Google Play and target-market rules for SMS, call-log, installed-app and background execution access.
- Threat-model exported receivers, replay attacks, tampered clients, log disclosure and malicious file uploads.

No API keys, private keys or signing credentials should ever be added to this repository. If a secret is committed, revoke it before removing it from Git history.
