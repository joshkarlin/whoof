# Whoof

Whoof is an unofficial WHOOP companion for the Light Phone III. It is intended for its developers and other technically capable users, rather than as a consumer product.

The official WHOOP app on another phone remains responsible for pairing with the strap, synchronizing sensor data, updating firmware, and sending data to WHOOP. Whoof reads the resulting recovery, sleep, strain, and workout data through the official WHOOP Developer API and presents a focused Light Phone interface.

Whoof is not affiliated with or endorsed by WHOOP or Light. It is a general-wellness tool, not a medical device, and must not be used to make medical decisions.

See the [privacy policy](PRIVACY.md) for the data Whoof processes and stores.

## Status

Whoof is an early, developer-oriented build. The Light SDK tool accepts a locally generated v2 provisioning QR code, stores the WHOOP OAuth credentials encrypted on-device, synchronizes the last 14 days of recovery, cycle, sleep, and workout records into a local Room cache, and keeps the last 30 days for offline viewing.

The Today screen shows recovery, strain, and sleep dials, HRV and resting heart rate with a trend against the previous seven days, time asleep, and the day's WHOOP activities with heart-rate zone bars. Only the activity list scrolls. Each dial opens a history screen for that metric; a day in strain history opens that day's activities, and tapping a day in sleep history expands its stage breakdown, sleep need, and respiratory rate. Pulling down on Today starts a sync and reveals WHOOP's latest record update separately from Whoof's last successful sync; sync failures stay visible beneath the activities. Settings provides manual sync, token replacement (which clears the previous cache), and a confirmed disconnection that deletes local data.

OAuth authorization remains on the developer's local computer rather than inside the Light Phone app. Serialized on-device refresh-token rotation is implemented and unit-tested, but still requires live device validation. Whoof synchronizes when it opens and when Today is shown again after five minutes. After a failed sync, automatic retries back off from five minutes to one hour, and a WHOOP rate-limit response holds automatic sync for 30 minutes; Sync now in Settings always tries immediately. Periodic background synchronization is not yet implemented.

Whoof cannot start, stop, or log activities. The WHOOP Developer API is read-only, so activities come from the official WHOOP app or WHOOP's automatic detection.

The initial architecture is deliberately constrained to capabilities available in the Light SDK so that the project can be considered for Light's community-tool build and submission process.

## Product direction

### Initial scope

- Show recovery, day strain, sleep, and the day's workouts
- Show per-metric history for recovery, strain, and sleep
- Cache records for offline viewing
- Planned: refresh through Light SDK background work
- Allow the user to disconnect and delete local data; WHOOP-side access revocation is not yet implemented

### Operating model

- The WHOOP strap remains paired with the official WHOOP app on another device
- Each Whoof user creates and owns a WHOOP Developer application
- OAuth credentials are supplied at runtime and are never built into Whoof
- Whoof communicates directly with WHOOP; the documented Developer API remains the normal read path, with a separate experimental private path for activity creation
- Whoof has no developer-operated backend, account system, telemetry, or data relay

### Non-goals for the initial version

- Pairing the Light Phone directly with a WHOOP strap
- Replacing the official WHOOP app or subscription
- Reimplementing WHOOP's analytics from raw sensor data
- Live heart rate, strap battery, firmware updates, alarms, or strap configuration
- Webhooks or push infrastructure
- Consumer-friendly, one-tap onboarding

A direct-Bluetooth, fully on-device implementation similar to NOOP may be researched separately. It is not part of the initial architecture because it would take ownership of the strap and currently requires Bluetooth and persistent-service capabilities that the Light SDK does not expose.

## POC Setup

The local setup helper performs the WHOOP Authorization Code flow in your browser, receives the one-time authorization code on a loopback HTTP server, exchanges it directly with WHOOP, and generates the existing `whoof://` provisioning QR code. Postman is not required.

The current helper requests `read:recovery read:cycles read:sleep read:workout offline`. These are the read-only data domains committed to the roadmap, and requesting them together avoids repeating provisioning as the Today and history screens are added. The `offline` scope is required for WHOOP to issue the rotating refresh token used for on-device refresh. Whoof does not request `read:profile` or `read:body_measurement`. Each developer or user must use a WHOOP Developer application they control; the helper does not send credentials to a Whoof server.

### Before you start

- Keep the official WHOOP app set up on another phone so it remains responsible for strap pairing and synchronization.
- Create or use a WHOOP Developer application that you control, following [WHOOP's getting-started documentation](https://developer.whoop.com/docs/developing/getting-started/).
- In the WHOOP Developer Dashboard, register this redirect URI exactly:
  `http://127.0.0.1:8765/callback`
- Request or configure `read:recovery`, `read:cycles`, `read:sleep`, `read:workout`, and `offline` only.
- Set the privacy policy URL to `https://github.com/joshkarlin/whoof/blob/main/PRIVACY.md` and provide a contact email that you monitor.
- Install [uv](https://docs.astral.sh/uv/getting-started/installation/).

The WHOOP Developer Dashboard accepted this fixed loopback redirect during POC testing on August 2, 2026. The helper deliberately fixes the host, port, and path so the registered value and request value cannot drift. Any future redirect change must be made in both the dashboard and this helper.

### Run local provisioning

From the repository root:

```sh
uv run --frozen python setup/provision_access_token.py --output ./whoof-provision.png
```

The helper prompts for the client ID and prompts for the client secret with terminal echo disabled. It opens the WHOOP authorization page in your default browser. After you authorize, WHOOP redirects to the local callback, the helper validates a cryptographically random `state` value, and it exchanges the authorization code at WHOOP's documented endpoints. The client ID and secret are accepted only in memory; they are not command-line arguments, written to disk, or logged. The client secret and access token are never placed in a URL; the browser URL contains only WHOOP's required authorization parameters, including `client_id`, `redirect_uri`, `scope`, and `state`. Authorization codes are not printed or persisted.

The helper has a bounded five-minute callback timeout and listens only on `127.0.0.1:8765`. It requests `read:recovery read:cycles read:sleep read:workout offline`. The QR is generated locally with the Python `qrcode` package; do not paste credentials into an online QR generator or upload the generated image to a QR service.

Open the local `whoof-provision.png` on the computer and scan it from Whoof's `CONNECT WHOOP` screen. The v2 image contains the developer client ID and secret plus access and refresh tokens. It grants control over this WHOOP integration and must be treated like a password. Delete it after scanning and do not email, commit, back up, or otherwise share it. The helper refuses to overwrite an existing image unless `--overwrite` is supplied explicitly.

### Token lifetime and replacement

Whoof now performs one serialized refresh after an API request returns `401`, persists WHOOP's replacement token pair, and retries the original request once. Refresh has unit and build verification but still requires live on-device validation with a v2 QR. Until that validation succeeds, retain the ability to repeat local authorization and provision a replacement QR.

## Architecture

### Local SDK signing

This repository inherits `sdk/keys/lightsdk-dev.jks` and its history directly from the official
[Light SDK](https://github.com/lightphone/light-sdk). Light publishes that certificate for local SDK
development and the LightOS emulator; production LightOS does not treat it as a Light-approved
signing certificate. Do not replace it or place production or private signing material in this
repository.

The repository is expected to contain two independently buildable parts:

```text
whoof/
  tool/       Light SDK application installed on the Light Phone
  setup/      Local developer provisioning helper
  docs/       Architecture, privacy, setup, and submission documentation
```

### Light tool

The Light Phone application will use only SDK-supported facilities:

- Kotlin and Compose
- Light SDK UI and navigation primitives
- Ktor or OkHttp for HTTPS requests
- Kotlin serialization for API payloads
- Room for cached WHOOP records
- DataStore for nonsensitive settings and synchronization metadata
- Android Keystore and AES-GCM for OAuth credentials
- `LightWork` for unique periodic synchronization
- `LightQrCodeScanner` for provisioning

Expected permissions are `INTERNET`, `ACCESS_NETWORK_STATE`, and `CAMERA`. No custom Android manifest, service, receiver, or unsupported Android API should be required.

### Setup helper

The setup helper is not installed on the Light Phone and is not part of the APK. In this POC, OAuth authorization happens locally through the helper's browser and loopback callback; it produces a one-time provisioning bundle. The bundle format is versioned and validated, and the token is not logged.

### Token handling

- The QR payload is versioned as `whoof://provision?v=1&access_token=...`.
- The current v2 payload contains the client ID, client secret, access token, rotating refresh token, and access-token lifetime required for on-device refresh. The parser retains v1 compatibility.
- The Light SDK tool validates the exact field set before storing all credentials together with Android Keystore-backed AES-GCM.
- The setup helper never logs the token and sends credentials only to WHOOP's documented OAuth endpoints.
- A v2 QR exposed before deletion compromises both the user authorization and the developer application secret. Revoke the authorization and rotate the developer client secret in the WHOOP dashboard before provisioning again.

Serialized refresh and persistence of WHOOP's replacement tokens are implemented. Live token-rotation and interrupted-persistence behavior still require device validation.

### Provisioning threat model

- The local computer, its browser, the displayed QR image, and the Light Phone are trusted during provisioning; no intermediary service is trusted or required.
- The QR image is plaintext by necessity and is the highest-risk transient artifact. The helper creates it with owner-only file permissions and never overwrites an existing file implicitly; the user must delete it immediately after a successful scan.
- Credentials are accepted only from an exact, size-bounded v1 or v2 payload and are encrypted before persistence. Unknown, duplicate, malformed, control-character, and oversized fields are rejected.
- Android Keystore protects credentials at rest but does not protect a compromised, unlocked device or a malicious process with equivalent application privileges.
- WHOOP rotates both access and refresh tokens. Future refresh logic must serialize requests and persist the replacement pair atomically; interruption after remote rotation but before local persistence may require reprovisioning.

## Light submission target

Whoof should remain eligible for Light's SDK-built tool pipeline from the beginning:

- Public source code
- A valid `lighttool.toml`
- Application code contained in the SDK `tool` module
- Only allowlisted dependencies, plugins, permissions, and Android APIs
- Successful build in Light's offline controlled builder
- No credentials, private artifacts, or generated secrets in the repository
- Light-native layout and navigation for the LP3's 1080x1240 display
- Clear privacy, setup, support, trademark, and wellness disclaimers

Passing the SDK builder does not guarantee that Light will classify Whoof as a Light-approved tool. Manual product, privacy, performance, and design review may impose additional requirements.

## Security and privacy principles

- Request only the read-only OAuth scopes committed to the roadmap; exclude profile and body-measurement access unless a concrete feature later requires them
- Store health data only on the Light Phone
- Encrypt OAuth credentials at rest with a non-exportable Keystore key
- Use TLS exclusively; use documented WHOOP endpoints except for the explicitly scoped experimental private integration
- Provide explicit API revocation and complete local-data deletion
- Collect no analytics, telemetry, crash reports, or user identifiers
- Treat API payloads, database exports, screenshots, and logs as sensitive health data

## Roadmap

This checklist is the source of truth for implementation progress. Update it when work starts, completes, or changes scope.

Experimental private-API work includes the local setup helper and a separate Android backend package, currently being prepared for UI integration. Existing screens continue to use the official Developer API. See [the private-API proof of concept](docs/private-api-poc.md) and [Android integration contract](docs/private-api-app.md). Private account credentials are separate from Developer OAuth and grant broader account access.

- [x] Build and unit-test private-API login, session refresh, and a read-only account check
- [x] Build and unit-test a local activity preview and explicitly confirmed submission command
- [x] Add and test macOS clipboard import to avoid terminal paste limits, with visible account-check progress
- [x] Validate private-API login against the user's account through macOS browser-session clipboard import (direct password sign-in still returns 401)
- [x] Submit one user-selected real activity and verify its type, time window, and SCORED status through the WHOOP API
- [x] Validate read-only private workout detail, sleep need, strain target, and detailed sleep endpoints ([results and schema notes](docs/private-api-read-tests.md))
- [x] Implement and unit-test the isolated Android private login/session provisioning, encrypted storage codec, and explicitly confirmed activity backend (46 tests pass in an isolated JVM harness; not device validation)
- [ ] Verify the full Android build with the private backend (blocked fetching Light keyboard `0.0.16`; GitHub Packages returns 401)
- [ ] Integrate private connection and activity controls into the UI, then validate login and one user-selected submission on the Light Phone

- [x] Implement the POC access-token provisioning bundle and recovery API call
- [x] Complete live local OAuth token exchange and QR provisioning
- [x] Store the provisioned access token encrypted on-device
- [x] Validate direct on-device WHOOP API access against a live account
- [x] Display live recovery and resting heart rate data on the Light Phone III
- [x] Define and threat-model the v2 provisioning bundle with refresh-token support
- [x] Implement validated v2 provisioning and encrypted storage for the complete refresh credential set
- [x] Implement and unit-test serialized token refresh and rotating-token persistence
- [x] Validate v2 provisioning on-device against a live WHOOP account
- [ ] Validate a live WHOOP token rotation on-device after the provisioned access token expires
- [x] Add explicit loading, authorization-expired, no-data, offline, and API-error states
- [x] Build and validate the Today screen for recovery, strain, sleep, RHR, HRV, and last-sync time
- [x] Redesign Today with dials, vitals trends, a scrolling activity log, and pull-to-sync (validated by hand on the LP3; screenshots not yet captured)
- [x] Add per-metric recovery, strain, and sleep history with expandable sleep rows and a day activity screen (validated by hand on the LP3; screenshots not yet captured)
- [x] Add Room caching and manual refresh (unit-tested; cached display and pull-to-sync validated on the LP3)
- [ ] Add periodic synchronization through a unique `LightWork` schedule
- [ ] Add disconnect with complete local-data deletion of credentials, cached records, and sync metadata (implemented and unit-tested; awaiting on-device validation)
- [ ] Add WHOOP access revocation from the application
- [ ] Test offline behavior, token expiry, interrupted refresh, token rotation, and API rate limiting
- [ ] Verify the controlled Light builder and prepare submission documentation

## Open questions

- Are there unpublished restrictions on using a client secret from an individually provisioned native application?
- What API update latency should users expect after the official app synchronizes a strap?
- Which privacy and review documents will Light require for a tool displaying health data?
- Should the setup helper support only macOS initially, or be cross-platform from its first release?
- Do the WHOOP-style data colours (especially yellow recovery and pale REM) keep enough contrast on the LP3 display and in the LightOS light theme?

## Known issues

- History covers the last 14 days fetched on each sync (up to four pages of 25 records per collection) and keeps 30 days locally. Older records are not backfilled.
- Recoveries are fetched as a single page of 25 and are upserted rather than window-replaced, so a recovery deleted in WHOOP stays cached until its cycle leaves the cache. Cycles, sleeps, and workouts deleted in WHOOP are removed from the window each sync covers.
- WHOOP's API returns sleep-stage totals, not a timeline, and day totals rather than heart-rate time series, so Whoof cannot draw a hypnogram or an intraday heart-rate chart.
- A day's calendar date is the wake date of its main sleep, or the cycle start shifted by 12 hours when there is no sleep record.

## References

- [WHOOP Developer documentation](https://developer.whoop.com/)
- [WHOOP OAuth documentation](https://developer.whoop.com/docs/developing/oauth/)
- [WHOOP API reference](https://developer.whoop.com/api/)
- [Light SDK](https://github.com/lightphone/light-sdk)
- [NOOP](https://github.com/ryanbr/noop), a separate direct-Bluetooth implementation and research reference
