# Whoof Agent Guide

Whoof is an unofficial WHOOP companion for the Light Phone III. It is a developer-oriented community tool, not a consumer product. Read `README.md` before proposing or implementing architectural changes.

## Product contract

- The official WHOOP app on another device remains the primary strap client.
- Whoof has one sign-in: the user's own WHOOP account session (email/password/MFA on the phone, or a local account QR). There is no WHOOP Developer application, client ID/secret, or OAuth provisioning; that path was removed and must not return without explicit user approval.
- Canonical recovery, sleep, cycle, and workout data come from WHOOP's `/developer/v2/` read endpoints, called with the account session's bearer token and the shared iOS-style headers from `WhoopPrivateHeaders`. This is unofficial; do not claim it is WHOOP's supported API.
- Account-session work is isolated to `setup/` and the Android `com.whoof.privateapi` package. Activity writes require a user-selected real session and explicit submission; login, provisioning, and connection checks must never create activities. Do not expand to other endpoints without a concrete request.
- Keep private transport, provisioning, persistence, and submission lifecycle code in `tool/src/main/kotlin/com/whoof/privateapi/`; see `docs/private-api-app.md` for the UI integration contract. Coordinate ownership before editing another agent's screens. Never retry an activity POST automatically, including after refresh, timeout, cancellation, or process restart.
- Each user signs in with their own WHOOP account; Whoof never embeds or shares credentials.
- Do not add a shared backend, credential broker, telemetry service, webhook receiver, or data relay without explicit user approval.
- Do not add direct WHOOP Bluetooth support to the initial application. Treat it as a separate future research track.
- Do not claim affiliation with or endorsement by WHOOP or Light.
- Do not present Whoof as a medical device or use health metrics to give medical advice.

## Light Phone and SDK

- Target the Light Phone III: Android, arm64-v8a, 1080x1240 at 480 dpi, with a nearly square 3.92-inch display.
- Build the installed application with the official Light SDK and keep it compatible with Light's controlled builder.
- Application code belongs in the SDK `tool` module. Do not provide a custom `AndroidManifest.xml`; declare approved permissions in `lighttool.toml`.
- Use Kotlin, Compose, coroutines, and Light SDK navigation and UI primitives.
- Use only dependencies, Gradle plugins, permissions, and Android APIs allowed by the pinned Light SDK.
- Prefer SDK abstractions such as `LightWork` and `LightQrCodeScanner` over direct Android framework access.
- Before adding a capability, verify it against the pinned SDK source and builder rather than assuming ordinary Android APIs are available.
- Keep the primary flows usable on the LP3's nearly square viewport. Verify UI changes on the LightOS emulator or real hardware.

## Architecture

The intended repository layout is:

```text
tool/   Light SDK application
setup/  Local account-session provisioning and research helpers
docs/   Architecture, privacy, setup, and submission documents
```

- Keep the setup helper independent from the Android APK and Light builder.
- The setup helper may sign in locally and generate a one-time account QR code. Transmit credentials only to the fixed WHOOP endpoints authorized above.
- Keep WHOOP transport models separate from persisted entities and UI models.
- Cache API records in Room so the application remains useful offline.
- Use DataStore only for nonsensitive preferences, synchronization metadata, or authenticated-encrypted credential/submission blobs. Never store plaintext account sessions or activity drafts there.
- Use Android Keystore with authenticated encryption for the account session.
- Centralize API endpoint construction, headers, session refresh behavior, retry policy, and redaction.

## Account session invariants

WHOOP's account session refreshes through Cognito; refresh tokens rotate (the scanned QR refresh token is single-use), and the access token lasts about 24 hours.

- Allow exactly one refresh operation at a time within the process, under `WhoopPrivateService`'s operation lock.
- Route every foreground and background read through `WhoopPrivateService.accessToken()`; never read the stored session elsewhere to call WHOOP.
- Persist replacement credentials immediately after refresh succeeds.
- A read that returns 401 may force one refresh and retry that GET once per sync. Never refresh or retry around an activity POST or DELETE.
- Never retry a refresh concurrently or speculatively. A refresh WHOOP rejects means the user must sign in again.
- Treat interruption between remote rotation and local persistence as requiring a new sign-in unless WHOOP documents a safe recovery mechanism.
- Validate provisioning bundle versions and required fields before storing anything.
- Never commit, print, log, snapshot, or include credentials in test fixtures.

## Security and privacy

- Health records and the account session are sensitive data. The session can act on the whole WHOOP account, so treat it like a password.
- Read only the collections used by implemented product behavior. New endpoints require an explicit README update and review.
- Use fixed WHOOP HTTPS endpoints only: authentication, account check, `/developer/v2/` reads, and activity create/delete. Keep credentials out of output and activity submissions free of automatic retries. The additional private detail-read probes remain local research until explicitly integrated.
- Redact authorization headers, query parameters, tokens, secrets, email addresses, user IDs, and health values from logs and exceptions.
- Use synthetic or irreversibly redacted API fixtures in tests.
- Provide complete local-data deletion (sign out clears the session, cache, sync time, timer, and recents; Delete local data also clears the no-replay marker) and, when WHOOP supports it, session revocation.
- Do not add analytics, crash reporting, advertising, or tracking SDKs.
- Do not use WHOOP logos, screenshots, fonts, or other proprietary visual assets.

## Background synchronization

- Use a unique `LightWork` schedule; do not create overlapping periodic jobs.
- Expect the platform's minimum periodic interval and non-exact execution time.
- Refresh when the application opens as well as periodically.
- Make freshness explicit: distinguish the WHOOP record's update time from Whoof's last successful synchronization.
- Apply bounded retry and backoff for transient failures and respect HTTP 429 responses.
- Preserve cached data when synchronization fails and surface a concise status instead of replacing it with an empty state.

## Engineering standards

- Keep changes focused and follow patterns already established in the repository and pinned SDK.
- Parse authentication and API payloads with structured parsers. Do not extract fields with ad hoc string manipulation.
- Separate pure domain logic from Android and SDK integration so it can be unit tested on the JVM.
- Add focused tests for token rotation, redaction, provisioning validation, pagination, time zones, partial records, offline caching, and API errors.
- Treat missing or unscored WHOOP fields as explicit states, not zero values.
- Use UTC instants for transport and persistence; convert to a display timezone only at the UI boundary.
- Do not silently broaden endpoints, permissions, network destinations, or background behavior.

## Releases and SDK upgrades

- Whoof is a fork of the Light SDK repository, as the SDK README recommends; upgrade by merging an SDK release tag into `main`, keeping Whoof's README, `tool/lighttool.toml` and CI, and both local SDK changes: the `lightSdk.directCameraPermission` camera flag (`sdk/client/build.gradle.kts`, `sdk/client/.../LightClientUiUtils.kt`, `gradle.properties`) and the `icon` field in `lighttool.toml` (`plugin/` metadata and manifest generator, `docs/tool_metadata/README.md`).
- `refs/tags/v*` on `origin` are Whoof releases only: pushing one runs `.github/workflows/release.yml`. Keep the SDK's tags out of `refs/tags`, because they use the same `vX.Y.Z` names. In a clone, set `git config remote.upstream.tagOpt --no-tags` and `git config --add remote.upstream.fetch '+refs/tags/*:refs/upstream-tags/*'`, then merge `upstream-tags/v0.2.3` and so on.
- Push release tags one at a time (`git push origin refs/tags/v0.2.0-rc.1`), never with `git push --tags`.
- Install on the LP3 with `adb install -r --no-incremental`. Releases ship the minified, non-debuggable release APK, signed with the SDK development key.

## Documentation and project state

- Keep `README.md` accurate when product scope, session behavior, permissions, or SDK constraints change.
- Treat the `README.md` roadmap as the source of truth for implementation tasks. Update it as work starts, completes, is blocked, or changes scope; do not leave task status to a later cleanup pass.
- Use coding subagents as workhorses for bounded implementation and verification tasks. Give each subagent a disjoint write scope, review its changes before integration, and keep security-sensitive session decisions under direct review.
- Record unresolved limitations under `Open questions` or a future `Known issues` section rather than implying unsupported behavior works.
- Keep setup instructions explicit about the required official WHOOP app, separate device and individual WHOOP account sign-in.
- Do not mark roadmap items complete until the behavior has been implemented and verified.
- Do not create, edit, or comment on external issues or pull requests without explicit user approval for that action.
