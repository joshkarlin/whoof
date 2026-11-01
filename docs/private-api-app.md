# Android private API integration

## Ownership

The Android backend lives exclusively in `tool/src/main/kotlin/com/whoof/privateapi/`, with tests in the matching test package. The Developer-API OAuth path no longer exists: this service is the app's only credential source, and `WhoopApi` (the `/developer/v2/` reads) takes its bearer token from `accessToken()`. Do not duplicate private HTTP, header, or token logic in screens.

| File | Responsibility |
| --- | --- |
| `WhoopPrivateClient.kt` | Fixed WHOOP endpoints, password/MFA authentication, account-session refresh, account check, one-shot activity POST |
| `WhoopPrivateHeaders.kt` | The single builder for the iOS-style request headers, used by this client and the `/developer/v2/` reads |
| `PrivateWhoopModels.kt` | Separate account credentials, safe error categories, sports, draft and wire payload |
| `PrivateWhoopProvisioning.kt` | Strict, versioned private-session QR parser |
| `PrivateWhoopStore.kt` | Encrypted session and single draft/submission record |
| `WhoopPrivateService.kt` | Screen-facing operations, serialized session handling, durable no-replay activity lifecycle |

Use one shared service across the private screens:

```kotlin
val client = WhoopPrivateClient()
val store = EncryptedPrivateWhoopStore(lightContext.dataStore)
val service = WhoopPrivateService(store, client)
```

Create this in an appropriate application/screen-owner lifecycle, not on every recomposition. Reuse the existing SDK DataStore instance. Close the HTTP client only when its shared owner is finished. A process-wide service mutex also guards accidental duplicate service instances; this does not establish cross-process coordination.

## Connection

The proven live-account route is browser-session import. Direct password authentication returned HTTP 401 during local research even though browser login succeeded. The Android password/MFA code is experimental; do not present a 401 as proof that the user's password is incorrect.

Generate a private QR from the existing local account session:

```sh
uv run --frozen python setup/provision_private_session.py
```

The helper validates the account and writes `local/private-api/whoof-private-provision.png`. It refreshes the local session only if it has expired. The QR is v=2 by default; `--full-session` emits the larger v=1 QR, which no longer fits for browser-imported sessions. `--overwrite` is required to replace an existing image. Scan locally and delete the image after connection; never upload it or use an online QR generator.

The default URI is `whoof://private-provision?v=2&refresh_token=...` with exactly those two fields (a browser-session access token is about 1,200 characters and the refresh token 1,700-1,800, so the v=1 form exceeds the 2,900-byte QR budget). On scan the phone refreshes through the Cognito proxy, persists the returned session immediately, then verifies the account against WHOOP. `v=1` (`access_token`, `refresh_token`, `expires_at` as absolute Unix seconds) is still parsed. The parser rejects any other QR, extra/duplicate fields, oversized tokens/payloads, and (for v=1) expiry within 60 seconds. Decoding a QR alone does not establish that a session is genuine.

Refresh tokens rotate: REFRESH_TOKEN_AUTH was live-verified from the computer on 2026-11-01 (it returns a 24-hour access token and a new refresh token), so treat the scanned token as single-use. Once the phone connects, the session saved on the computer stops working; delete the QR image after scanning. If the phone cannot refresh (Unauthorized), it shows "Session expired. Make a new QR." If the replacement token has been persisted but the account check fails, the phone keeps the new session. If the refresh response omits a new refresh token, the scanned one is kept.

Screen-facing calls:

- `provision(scannedText)` verifies and saves a private session.
- `beginLogin(email, password)` returns `Authenticated` or `VerificationRequired`.
- `completeLogin(challenge, code)` handles the supported verification challenge. Keep challenge and password only in memory; clear UI inputs after use and never log them.
- `checkConnection()` refreshes when needed and checks authenticated account access without returning personal account details.
- `accessToken(forceRefresh = false, rejectedToken = null)` returns the bearer token for `/developer/v2/` reads. Under the same operation lock it refreshes first when the session is within 60 seconds of expiry and saves the rotated session before returning. `forceRefresh` is for a GET that returned 401: it refreshes unless `rejectedToken` is no longer the stored token (another caller already rotated it). A missing session, or a refresh WHOOP rejects, throws `ReconnectRequired`. The synchronizer forces at most one refresh per sync and retries the rejected GET once.
- `isSignedIn()` reports whether a session is stored.
- `state()` loads the private session and draft. Treat all fields as sensitive; do not dump them into logs or saved screen state.
- `disconnect()` removes private credentials and prepared drafts, retaining any attempted-submission marker to prevent silent replay. It does not revoke the server session. `DefaultWhoofRepository.signOut()` calls it and then clears the Room cache, sync time, activity timer, and recents.
- `deleteLocalData(confirmed = true)` explicitly deletes the private session and draft/receipt marker. This is local deletion, not server revocation or deletion of a WHOOP activity. `DefaultWhoofRepository.deleteLocalData()` calls it and clears the same local records. Warn before deleting an uncertain marker.

Private credentials use a separate encrypted DataStore preference record. Encryption uses the Keystore-backed AES-GCM cipher in `CredentialCipher.kt`. No passwords or MFA challenges are persisted. A missing refresh-token replacement retains the current Cognito refresh token.

## Activity flow

This creates a completed activity record, not a live band session or a Bluetooth command. It neither pairs with nor synchronizes the strap. The official WHOOP app still uploads sensor data.

1. Select a `PrivateWhoopSport` from `PrivateWhoopSports.all` (203 ids from WHOOP's US sports catalog) and a real, completed start/end window. Resolve local clock times and DST ambiguity into explicit `Instant` values in the UI before calling the service.
2. Call `prepareActivity(sport, start, end, ZoneId)`. The initial limit is one minute to 24 hours, ending no later than now; GPS is disabled. Preparing only saves a local draft.
3. Show the saved sport, start/end in the saved timezone, and duration for review. Submit only after an explicit user action with `submitActivity(draft.id, confirmed = true)`; never submit in initialization, a state collector, or navigation callbacks.
4. On success, the saved status is `Submitted` with an activity receipt. Receipt acceptance is not proof that scoring is finished or that all metrics exist.
5. HTTP 409 maps to `Overlap` and 422 to `TooShort`; both are definite rejections (draft becomes `Rejected`). `deleteActivity(activityId, confirmed = true)` sends one `DELETE .../core-details-bff/v1/cardio-details` after a refresh/account check, never retries it, treats `NotFound` as already deleted, and clears a matching `Submitted` draft.
6. On an uncertain outcome, keep the saved `Uncertain` marker and tell the user to check WHOOP before creating another draft. `discardDraft(confirmed = true)` is a separate deliberate action, not an automatic error recovery path.

The service persists `Uncertain` **before** the POST. Timeout, cancellation, process death, invalid receipt, or failure to persist an accepted receipt cannot turn that draft back into `Prepared`. A known rejection is stored as `Rejected`; the same draft is still not replayed. Double taps and a second service instance cannot submit the same draft twice within the process. This is conservative local duplicate prevention, not server idempotency: manually discarding records or reinstalling the app removes that protection.

An expired session is refreshed and saved before submission. An unauthorized account GET can refresh and retry once; an activity POST never triggers refresh/replay. Redirects and connection retries are disabled in the private HTTP client. No background activity submissions are scheduled.

## UI states and limits

Map `PrivateWhoopException.reason` to fixed, concise messages. Handle `ReconnectRequired`, `RateLimited`, `ConfirmationRequired`, `AlreadyAttempted`, and `SubmissionUncertain` distinctly. Never display raw HTTP bodies, exception causes, credentials, or personal health JSON. Cancellation should cancel the screen operation, not become an automatic resubmit.

The phone can scan the account QR from Sign in (camera icon, top right; unverified on the LP3). Today shows SIGN IN when no session exists or when the session cannot be refreshed. Browser-session refresh through the proxy was live-verified from the computer on 2026-11-01 but not yet from the phone. Remaining UI wiring, real-phone scanning, device persistence/refresh, and one explicitly selected live activity submission require separate verification. Local JVM tests do not prove those work on hardware. This package does not integrate private sleep/strain/detail endpoints or journal/alarms. No controlled-builder or Light-approval claim is implied by adding the backend.

## Verification

- 46 Kotlin tests passed against the production backend source in an isolated temporary JVM harness, including a loopback HTTP 503/`Retry-After: 0` no-replay check. The harness used the pinned Kotlin/Ktor/coroutine/serialization/DataStore versions and an Android compile-only jar; it excluded UI and does not establish APK or Keystore behavior on hardware.
- 83 Python setup tests passed offline, including nine new private QR-export tests. No live export or account write was performed during this implementation.
- Full `:tool:testDebugUnitTest` is blocked before compilation: `com.thelightphone.lp3keyboard:ui:0.0.16` is not cached and GitHub Packages returns HTTP 401 with the available login. No dependencies, SDK source, or UI files were changed to bypass this blocker.
