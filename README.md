# Whoof

Whoof is an unofficial WHOOP companion for the Light Phone III. It is intended for its developers and other technically capable users, rather than as a consumer product.

The official WHOOP app on another phone remains responsible for pairing with the strap, synchronizing sensor data, updating firmware, and sending data to WHOOP. Whoof signs in to your own WHOOP account and reads the resulting recovery, sleep, strain, and workout data, presenting a focused Light Phone interface. It uses the account session of WHOOP's mobile app (not the public Developer API's OAuth); that session reaches the same Developer v2 read endpoints.

Whoof is not affiliated with or endorsed by WHOOP or Light. It is a general-wellness tool, not a medical device, and must not be used to make medical decisions.

See the [privacy policy](PRIVACY.md) for the data Whoof processes and stores.

## Status

Whoof is an early, developer-oriented build. It has one sign-in: your WHOOP account, entered on the phone (email, password, and a verification code if WHOOP asks) or by scanning a local account QR code made by `setup/provision_private_session.py`. The session is stored encrypted on-device. Whoof synchronizes the last 14 days of recovery, cycle, sleep, and workout records into a local Room cache and keeps the last 30 days for offline viewing.

Today's data comes from WHOOP's `/developer/v2/` read endpoints (cycle, recovery, sleep, workout), authorized with the account session's bearer token and the same iOS-style headers the private client uses. A read-only check on 2026-11-01 confirmed that these endpoints return the public Developer API v2 shapes (records plus `next_token`) for a private-session token. That check was made from a computer; the integration is implemented and unit-tested but **unverified on the LP3**.

The Today screen shows recovery, strain, and sleep dials, HRV and resting heart rate with a trend against the previous seven days, time asleep, and the day's WHOOP activities with heart-rate zone bars. Only the activity list scrolls. Each dial opens a history screen for that metric; a day in strain history opens that day's activities, and tapping a day in sleep history expands its stage breakdown, sleep need, and respiratory rate. Pulling down on Today starts a sync and reveals WHOOP's latest record update separately from Whoof's last successful sync; sync failures stay visible beneath the activities. When signed out, Today shows a message and a SIGN IN button. Settings provides manual sync, the WHOOP account screen (SIGN OUT, which also clears cached records, the sync time, the activity timer, and recent sports), and a confirmed Delete local data that additionally removes the stored no-replay submission marker.

The access token is refreshed on-device when within 60 seconds of expiry, and once after a rejected read; refresh tokens rotate and each rotation is persisted immediately. If the session cannot be refreshed, Today asks you to sign in again. Whoof synchronizes when it opens and when Today is shown again after five minutes. After a failed sync, automatic retries back off from five minutes to one hour, and a WHOOP rate-limit response holds automatic sync for 30 minutes; Sync now in Settings always tries immediately. While signed in, a unique `LightWork` job (`whoof-sync`) also syncs about every 30 minutes through the same repository and account-session token path; the system decides the exact timing (minimum 15 minutes, often later), it does nothing while signed out, and sign-out or Delete local data cancels it. Session loss and rate limiting wait for the next interval, and only an unreachable network or WHOOP service asks the system to retry with backoff. Signing in as a new account first clears the previous account's cached records, pending activities, sync time, and activity timer (favourite and recent sports are kept). Requests send the phone's current time zone, falling back to Europe/London only if the phone reports an invalid id.

Whoof can log a completed or timed activity (and delete one) through WHOOP's private activity endpoints; see [the private-API app notes](docs/private-api-app.md). It cannot start or stop strap sessions.

The initial architecture is deliberately constrained to capabilities available in the Light SDK so that the project can be considered for Light's community-tool build and submission process.

## Product direction

### Initial scope

- Show recovery, day strain, sleep, and the day's workouts
- Show per-metric history for recovery, strain, and sleep
- Cache records for offline viewing
- Refresh through Light SDK background work (implemented, unverified on the device)
- Allow the user to disconnect and delete local data; WHOOP-side access revocation is not yet implemented

### Operating model

- The WHOOP strap remains paired with the official WHOOP app on another device
- Each Whoof user signs in with their own WHOOP account; no credentials or client secrets are built into Whoof
- There is no WHOOP Developer application, client ID/secret, or OAuth provisioning step. The earlier Developer-API OAuth path was removed on 2026-11-01
- Whoof communicates directly with WHOOP using the account session, for both reads and activity logging
- Whoof has no developer-operated backend, account system, telemetry, or data relay

The account session is broader than a Developer API token: it can act on the whole WHOOP account, not only read the four data domains. Whoof only reads the four collections and logs/deletes activities, but anyone who holds the session or its QR code can do more. WHOOP may change or restrict this unofficial access at any time.

### Non-goals for the initial version

- Pairing the Light Phone directly with a WHOOP strap
- Replacing the official WHOOP app or subscription
- Reimplementing WHOOP's analytics from raw sensor data
- Live heart rate, strap battery, firmware updates, alarms, or strap configuration
- Webhooks or push infrastructure
- Consumer-friendly, one-tap onboarding

A direct-Bluetooth, fully on-device implementation similar to NOOP may be researched separately. It is not part of the initial architecture because it would take ownership of the strap and currently requires Bluetooth and persistent-service capabilities that the Light SDK does not expose.

## Setup

### Before you start

- Keep the official WHOOP app set up on another phone so it remains responsible for strap pairing and synchronization.
- You need a WHOOP account. There is no developer account, client ID, or client secret to create.

### Sign in on the phone

Open Whoof and choose SIGN IN on Today. Enter your WHOOP email and password and, if WHOOP sends one, the verification code. The SDK text editor does not mask the password while you type it. Earlier direct password attempts returned HTTP 401; if that happens to you, use the QR route.

### Sign in with an account QR

On a computer you trust, generate a one-time QR code from a local WHOOP browser session (see [the private-API proof of concept](docs/private-api-poc.md)):

```sh
uv run --frozen python setup/provision_private_session.py
```

The helper writes `local/private-api/whoof-private-provision.png`. On the phone choose SIGN IN, then the camera icon (SCAN ACCOUNT QR). The default `v=2` QR holds only a refresh token; the phone exchanges it, so it is single-use and the session saved on the computer stops working. The QR is plaintext and grants control of your WHOOP account: delete it after scanning and never email, commit, back up, upload, or generate it with an online service.

### Signing out and deleting data

Settings > WHOOP account > SIGN OUT ends the session and clears the cache, sync time, activity timer, and recent sports. Settings > Delete local data does the same and also removes the stored no-replay activity-submission marker (confirmation required). Neither revokes the session on WHOOP's servers; to invalidate it, change your WHOOP password or sign out of other sessions in WHOOP.

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

The Light Phone application uses only SDK-supported facilities:

- Kotlin and Compose
- Light SDK UI and navigation primitives
- Ktor or OkHttp for HTTPS requests
- Kotlin serialization for API payloads
- Room for cached WHOOP records
- DataStore for nonsensitive settings and synchronization metadata
- Android Keystore and AES-GCM for the account session
- `LightWork` for unique periodic synchronization (planned)
- `LightQrCodeScanner` for the account QR

Expected permissions are `INTERNET`, `ACCESS_NETWORK_STATE`, and `CAMERA`. No custom Android manifest, service, receiver, or unsupported Android API should be required.

### Setup helper

The setup helper is not installed on the Light Phone and is not part of the APK. `setup/provision_private_session.py` exports a one-time account QR from a local session; `setup/private_api_poc.py` and `setup/private_read_probe.py` are research tools. The Developer OAuth helper (`provision_access_token.py`) was removed.

### Session handling

- The sole credential is the WHOOP account session (access token, rotating refresh token, expiry), stored in an encrypted preference record using Android Keystore AES-GCM.
- `WhoopPrivateService.accessToken()` is the only source of bearer tokens for reads. It returns the stored token, refreshing first when it is within 60 seconds of expiry, and persists the rotated pair before returning.
- A read that returns 401 may force one refresh and be retried once; a second 401 means the session must be re-established. Refresh is never retried or run concurrently, and all callers share one operation lock. Activity POSTs never trigger a refresh or retry.
- Any refresh the account rejects (Unauthorized or Rejected) is reported as "sign in again". Interruption between remote rotation and local persistence may require signing in again.
- Request headers for all WHOOP calls come from one place (`WhoopPrivateHeaders`), including `x-whoop-time-zone`, which is the phone's current zone read per request (Europe/London only as a fallback for an invalid id).
- Passwords and verification codes are held in memory only and never persisted or logged.

### Account QR threat model

- The local computer, its browser, the displayed QR image, and the Light Phone are trusted during provisioning; no intermediary service is trusted or required.
- The QR image is plaintext by necessity and is the highest-risk transient artifact. The helper creates it with owner-only file permissions and never overwrites an existing file implicitly; delete it immediately after a successful scan.
- Payloads are accepted only if exact, size-bounded, and well-formed; unknown, duplicate, malformed, and oversized fields are rejected, and the session is encrypted before persistence.
- Android Keystore protects the session at rest but does not protect a compromised, unlocked device or a malicious process with equivalent application privileges.
- WHOOP rotates the refresh token on each use, so the scanned token is single-use.

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

- Read only recovery, cycle, sleep, and workout collections (plus the account check and the activity create/delete calls); do not add other endpoints without a README update and review
- Store health data only on the Light Phone
- Encrypt the account session at rest with a non-exportable Keystore key
- Use TLS exclusively and talk only to fixed WHOOP endpoints; the account-session integration is unofficial and undocumented by WHOOP
- Provide complete local-data deletion; server-side revocation is not implemented
- Collect no analytics, telemetry, crash reports, or user identifiers
- Treat API payloads, database exports, screenshots, and logs as sensitive health data

## Roadmap

This checklist is the source of truth for implementation progress. Update it when work starts, completes, or changes scope.

Whoof now has a single sign-in, the WHOOP account session described above. See [the private-API proof of concept](docs/private-api-poc.md) and [Android integration notes](docs/private-api-app.md). Account credentials grant broader access than a Developer API token.

- [x] Build and unit-test private-API login, session refresh, and a read-only account check
- [x] Build and unit-test a local activity preview and explicitly confirmed submission command
- [x] Add and test macOS clipboard import to avoid terminal paste limits, with visible account-check progress
- [x] Validate private-API login against the user's account through macOS browser-session clipboard import (direct password sign-in still returns 401)
- [x] Submit one user-selected real activity and verify its type, time window, and SCORED status through the WHOOP API
- [x] Validate read-only private workout detail, sleep need, strain target, and detailed sleep endpoints ([results and schema notes](docs/private-api-read-tests.md))
- [x] Implement and unit-test the isolated Android private login/session provisioning, encrypted storage codec, and explicitly confirmed activity backend (46 tests pass in an isolated JVM harness; not device validation)
- [ ] Verify the full Android build with the private backend (blocked fetching Light keyboard `0.0.16`; GitHub Packages returns 401)
- [x] Add on-device WHOOP account sign-in (Today's SIGN IN: email, password, optional verification code; Cognito `__type` errors mapped to fixed messages; sign out from Settings > WHOOP account; the Sign in camera icon scans the v=2 private-session QR (refresh token only; the phone refreshes and rotates it) from `setup/provision_private_session.py`, unverified on the LP3; browser-session refresh was live-verified from the computer on 2026-11-01 but not from the phone). Unit-tested on the JVM; not yet verified on the LP3
- [x] Integrate private activity controls into the UI (implemented, unit-tested on the JVM, unverified on the device): Today's ACTIVITY row shows + (or the running timer) when signed in privately; sport search with recents, a persisted START/STOP timer or a past-activity time entry, review with adjustable start/end, and one explicit SUBMIT TO WHOOP (overlap and uncertain-submission handling, never auto-retried); tapping an activity opens it with a confirmed delete from WHOOP that also removes it from the local cache
- [x] Sport picker redesign (implemented, JVM-tested, unverified on the LP3): opens on the list (no keyboard) with a top-right search icon; FAVOURITES (user-ordered, managed on a Favourites page with add, move up/down, remove), RECENT (up to 5, excluding favourites), then ALL; favourites are stored as nonsensitive DataStore ids and cleared with recents on sign-out and Delete local data
- [x] Show newly logged activities on Today before WHOOP lists them (implemented, JVM-tested, unverified on the LP3): a submit records a pending row in a separate Room database (`whoof_pending.db`; the SDK database builder cannot register migrations), merged into its cycle with a PENDING label, dropped when sync returns the id or after 48 hours, and cleared on sign-out and Delete local data
- [ ] Validate activity logging and deletion on the Light Phone with one user-selected submission and one deletion

- [x] Developer API OAuth removed (2026-11-01): OAuth credentials, provisioning QR v1/v2, `provision_access_token.py`, the OAuth token coordinator, and Settings "Replace token" are deleted; this is no longer a supported path
- [x] Today and history via the private account session: reads use the session's bearer token against `/developer/v2/` with the private client's headers (implemented and unit-tested; unverified on the LP3)
- [ ] Verify on the LP3: sign-in, first sync, refresh near expiry and after a rejected read, sign-out, and Delete local data
- [x] Add explicit loading, session-expired, no-data, offline, and API-error states
- [x] Build and validate the Today screen for recovery, strain, sleep, RHR, HRV, and last-sync time
- [x] Redesign Today with dials, vitals trends, a scrolling activity log, and pull-to-sync (validated by hand on the LP3; screenshots not yet captured)
- [x] Add per-metric recovery, strain, and sleep history with expandable sleep rows and a day activity screen (validated by hand on the LP3; screenshots not yet captured)
- [x] Add Room caching and manual refresh (unit-tested; cached display and pull-to-sync validated on the LP3)
- [x] Add periodic synchronization through a unique `LightWork` schedule (implemented and JVM-tested, unverified on the device): `@LightJob("whoof-sync")`, `enqueuePeriodic` every 30 minutes (UPDATE policy) on app start and after sign-in when signed in, cancelled on sign-out and Delete local data; the system decides exact timing
- [x] Clear the previous account's cache, pending activities, sync metadata, and timer when a new session is accepted; send the phone's current time zone instead of a fixed one (JVM-tested, unverified on the device)
- [ ] Sign out and Delete local data clear the session, cached records, sync metadata, activity timer, and recents (implemented and unit-tested; awaiting on-device validation)
- [ ] Add server-side session revocation from the application
- [ ] Test offline behavior, token expiry, interrupted refresh, token rotation, and API rate limiting on the device
- [ ] Verify the controlled Light builder and prepare submission documentation

## Open questions

- Will WHOOP keep accepting the mobile app's account session on `/developer/v2/` endpoints, and are there rate limits or terms that apply to it?
- What API update latency should users expect after the official app synchronizes a strap?
- Which privacy and review documents will Light require for a tool displaying health data?
- Should the setup helper support only macOS initially, or be cross-platform from its first release?
- Does on-device password sign-in succeed on the LP3? Earlier direct password attempts returned HTTP 401; the sign-in screens now surface the specific Cognito error type. The SDK text editor has no password masking or numeric keyboard, so the password is visible while typed.
- Do the WHOOP-style data colours (especially yellow recovery and pale REM) keep enough contrast on the LP3 display and in the LightOS light theme?

## Known issues

- History covers the last 14 days fetched on each sync (up to four pages of 25 records per collection) and keeps 30 days locally. Older records are not backfilled.
- Recoveries are fetched as a single page of 25 and are upserted rather than window-replaced, so a recovery deleted in WHOOP stays cached until its cycle leaves the cache. Cycles, sleeps, and workouts deleted in WHOOP are removed from the window each sync covers.
- WHOOP's API returns sleep-stage totals, not a timeline, and day totals rather than heart-rate time series, so Whoof cannot draw a hypnogram or an intraday heart-rate chart.
- A day's calendar date is the wake date of its main sleep, or the cycle start shifted by 12 hours when there is no sleep record.

## References

- [WHOOP Developer documentation](https://developer.whoop.com/), for the shape of the v2 read responses
- [WHOOP API reference](https://developer.whoop.com/api/)
- [Light SDK](https://github.com/lightphone/light-sdk)
- [NOOP](https://github.com/ryanbr/noop), a separate direct-Bluetooth implementation and research reference
