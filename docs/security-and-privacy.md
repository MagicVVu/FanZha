# Security and privacy

FanZha may process communications, account identifiers, uploaded content, device metadata and model prompts. A successful build is not evidence that the system is ready for public distribution.

## Existing safeguards

**Android**

- Device collection starts behind an in-app consent gate and runtime permissions.
- Unavailable sources are skipped instead of blocking unrelated features.
- SMS/call ingestion uses timestamp watermarks; clipboard and app uploads are deduplicated.
- HTTP body logging is disabled in release builds.
- Local configuration and signing material are ignored by Git.

**Backend**

- Passwords are encoded with BCrypt cost 12; legacy salted SHA-256 values are rehashed after a successful login.
- Database, model, OSS, proxy and crawler credentials are environment-based.
- Browser origins default to localhost patterns rather than a global wildcard.
- Crawler, scheduled extraction, Chroma startup initialization, ingestion and admin endpoints are disabled by default.
- Docker runs the application as a non-root user.
- Runtime datasets, crawler checkpoints, Playwright profiles and vector files are excluded from source control.

## Current security gaps

- Login does not issue an access/refresh token; endpoint authorization is not implemented.
- AI chat can consume a paid upstream API and has no application-level rate limit.
- Upload and admin routes must not be enabled on a public network without an authenticated control plane.
- Manual jobs run in-process and do not have durable ownership, audit or cancellation semantics.
- The Android client stores some state in ordinary SharedPreferences.
- Full data retention, export and deletion workflows are not implemented.
- External crawler targets can change behavior or terms; operators must assess legality, robots policy and content licensing.

## Sensitive Android permissions

| Permission | Purpose | Required safeguard |
| --- | --- | --- |
| `READ_SMS` / `RECEIVE_SMS` | Suspicious SMS detection | Explicit consent, minimization and graceful denial |
| `READ_CALL_LOG` | Suspicious call analysis | Purpose limitation and retention controls |
| `QUERY_ALL_PACKAGES` | Suspicious-app checks | Store-policy justification and narrow alternatives |
| `POST_NOTIFICATIONS` | Risk alerts | Android 13+ runtime request |
| `SCHEDULE_EXACT_ALARM` | Scheduled risk polling | Battery/policy review and push-based replacement |
| `RECEIVE_BOOT_COMPLETED` | Restore scheduled checks | Visible user control to disable monitoring |

## Production requirements

1. Add token issuance, rotation, revocation, role-based authorization and object ownership checks.
2. Terminate TLS at a trusted proxy and restrict Actuator/admin routes by network and identity.
3. Add rate limits, request IDs, audit events and stable error codes.
4. Scan uploads in isolation; enforce allowlisted types, decompression limits and retention policies.
5. Store secrets in a managed secret service and use a least-privilege database account.
6. Encrypt sensitive local/client data and backups; document retention and deletion SLAs.
7. Publish a privacy policy listing fields, purposes, recipients, retention and withdrawal routes.
8. Threat-model exported Android components, replay, tampered clients, prompt injection, SSRF, log disclosure and malicious archives.
9. Review Google Play and target-market rules for communications, application inventory and background execution.

## Secret incident handling

Never commit API keys, private keys, database passwords, cookies or signing identities. If a secret reaches Git, revoke or rotate it first; deleting the visible line or rewriting history alone does not make the credential safe.
