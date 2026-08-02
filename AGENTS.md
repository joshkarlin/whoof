# Whoof Agent Guide

Whoof is an unofficial, read-only WHOOP companion for the Light Phone III. It is a developer-oriented community tool, not a consumer product. Read `README.md` before proposing or implementing architectural changes.

## Product contract

- The official WHOOP app on another device remains the primary strap client.
- Use WHOOP's documented Developer API for canonical recovery, sleep, cycle, and workout data.
- Each user owns and provisions their own WHOOP Developer credentials.
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
setup/  Local OAuth provisioning helper
docs/   Architecture, privacy, setup, and submission documents
```

- Keep the setup helper independent from the Android APK and Light builder.
- The setup helper may perform OAuth locally and generate a one-time provisioning QR code. It must not transmit credentials anywhere except WHOOP's documented endpoints.
- Keep WHOOP transport models separate from persisted entities and UI models.
- Cache API records in Room so the application remains useful offline.
- Use DataStore only for nonsensitive preferences and synchronization metadata.
- Use Android Keystore with authenticated encryption for client secrets and OAuth tokens.
- Centralize API endpoint construction, OAuth behavior, retry policy, and redaction.

## OAuth invariants

WHOOP refresh tokens rotate. A successful refresh invalidates the previous access and refresh tokens.

- Allow exactly one refresh operation at a time within the process.
- Route foreground and background API calls through the same token coordinator.
- Persist replacement credentials immediately after refresh succeeds.
- Never retry a refresh concurrently or speculatively.
- Treat interruption between remote rotation and local persistence as requiring reprovisioning unless WHOOP documents a safe recovery mechanism.
- Validate provisioning bundle versions and required fields before storing anything.
- Never commit, print, log, snapshot, or include credentials in test fixtures.

## Security and privacy

- Health records and OAuth credentials are sensitive data.
- Request only scopes used by implemented product behavior. New scopes require an explicit README update and review.
- Use documented WHOOP HTTPS endpoints only.
- Redact authorization headers, query parameters, tokens, secrets, email addresses, user IDs, and health values from logs and exceptions.
- Use synthetic or irreversibly redacted API fixtures in tests.
- Provide complete local-data deletion and WHOOP access revocation.
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
- Parse OAuth and API payloads with structured parsers. Do not extract fields with ad hoc string manipulation.
- Separate pure domain logic from Android and SDK integration so it can be unit tested on the JVM.
- Add focused tests for token rotation, redaction, provisioning validation, pagination, time zones, partial records, offline caching, and API errors.
- Treat missing or unscored WHOOP fields as explicit states, not zero values.
- Use UTC instants for transport and persistence; convert to a display timezone only at the UI boundary.
- Do not silently broaden OAuth scopes, permissions, network destinations, or background behavior.

## Documentation and project state

- Keep `README.md` accurate when product scope, OAuth behavior, permissions, or SDK constraints change.
- Record unresolved limitations under `Open questions` or a future `Known issues` section rather than implying unsupported behavior works.
- Keep setup instructions explicit about the required official WHOOP app, separate device, developer account, and individual credentials.
- Do not mark roadmap items complete until the behavior has been implemented and verified.
- Do not create, edit, or comment on external issues or pull requests without explicit user approval for that action.
