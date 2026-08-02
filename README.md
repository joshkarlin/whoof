# Whoof

Whoof is an unofficial, read-only WHOOP companion for the Light Phone III. It is intended for its developers and other technically capable users, rather than as a consumer product.

The official WHOOP app on another phone remains responsible for pairing with the strap, synchronizing sensor data, updating firmware, and sending data to WHOOP. Whoof reads the resulting recovery, sleep, strain, and workout data through the official WHOOP Developer API and presents a focused Light Phone interface.

Whoof is not affiliated with or endorsed by WHOOP or Light. It is a general-wellness tool, not a medical device, and must not be used to make medical decisions.

See the [privacy policy](PRIVACY.md) for the data processed by the current proof of concept.

## Status

Whoof currently contains a minimal proof of concept. The Light SDK tool accepts a short-lived WHOOP access token through a locally generated QR code, calls the WHOOP Developer API, and displays the latest recovery record.

This POC deliberately does not implement OAuth inside the Light Phone app, refresh tokens, token rotation, or background synchronization. A new QR code must be generated when the access token expires.

The initial architecture is deliberately constrained to capabilities available in the Light SDK so that the project can be considered for Light's community-tool build and submission process.

## Product direction

### Initial scope

- POC: show the latest WHOOP recovery record
- Planned: show day strain, sleep, and workout data
- Planned: cache records for offline viewing and refresh through Light SDK background work
- Planned: allow the user to disconnect and delete local data

### Operating model

- The WHOOP strap remains paired with the official WHOOP app on another device
- Each Whoof user creates and owns a WHOOP Developer application
- OAuth credentials are supplied at runtime and are never built into Whoof
- Whoof communicates directly with WHOOP's official API
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

The POC uses only the `read:recovery` scope because the Light SDK tool currently calls the recovery endpoint. It does not request `offline`, does not request a refresh token, and never places a client secret in the QR code. Each developer or user must use a WHOOP Developer application they control; the helper does not send credentials to a Whoof server.

### Before you start

- Keep the official WHOOP app set up on another phone so it remains responsible for strap pairing and synchronization.
- Create or use a WHOOP Developer application that you control, following [WHOOP's getting-started documentation](https://developer.whoop.com/docs/developing/getting-started/).
- In the WHOOP Developer Dashboard, register this redirect URI exactly:
  `http://127.0.0.1:8765/callback`
- Request or configure `read:recovery` only. Do not add `offline`.
- Set the privacy policy URL to `https://github.com/joshkarlin/whoof/blob/main/PRIVACY.md` and provide a contact email that you monitor.
- Install Python 3 and the local QR dependency from this repository.

The WHOOP Developer Dashboard accepted this fixed loopback redirect during POC testing on August 2, 2026. The helper deliberately fixes the host, port, and path so the registered value and request value cannot drift. Any future redirect change must be made in both the dashboard and this helper.

### Run local provisioning

From the repository root:

```sh
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r setup/requirements.txt
python3 setup/provision_access_token.py --output ./whoof-provision.png
```

The helper prompts for the client ID and prompts for the client secret with terminal echo disabled. It opens the WHOOP authorization page in your default browser. After you authorize, WHOOP redirects to the local callback, the helper validates a cryptographically random `state` value, and it exchanges the authorization code at WHOOP's documented endpoints. The client ID and secret are accepted only in memory; they are not command-line arguments, written to disk, or logged. The client secret and access token are never placed in a URL; the browser URL contains only WHOOP's required authorization parameters, including `client_id`, `redirect_uri`, `scope`, and `state`. Authorization codes are not printed or persisted.

The helper has a bounded five-minute callback timeout and listens only on `127.0.0.1:8765`. It requests `read:recovery` only and sends no `offline` scope. The QR is generated locally with the Python `qrcode` package; do not paste credentials into an online QR generator or upload the generated image to a QR service.

Open the local `whoof-provision.png` on the computer and scan it from Whoof's `CONNECT WHOOP` screen. The image contains a bearer token and is sensitive. Delete it after scanning and do not email, commit, or otherwise share it. The helper refuses to overwrite an existing image unless `--overwrite` is supplied explicitly.

### Token lifetime and replacement

This is a short-lived access-token POC. Whoof stores the scanned token encrypted on the Light Phone, but it cannot refresh it. When Whoof reports that the access token has expired, repeat the local authorization flow and generate a new QR code. Do not add a refresh token to the QR payload: refresh-token support requires a separate implementation and review.

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

### Token handling in the POC

- The QR payload is versioned as `whoof://provision?v=1&access_token=...`.
- The QR contains only the short-lived access token. It does not contain a client ID, client secret, or refresh token.
- The Light SDK tool validates the payload before storing it and encrypts the token with Android Keystore-backed AES-GCM.
- The setup helper never logs the token and sends credentials only to WHOOP's documented OAuth endpoints.
- A token that is exposed should be treated as compromised; generate a replacement through WHOOP's official authorization flow.

Refresh-token rotation and serialized refresh are future work, not behavior provided by this POC.

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

- Request the minimum OAuth scopes needed by the implemented screens
- Store health data only on the Light Phone
- Encrypt OAuth credentials at rest with a non-exportable Keystore key
- Use TLS exclusively and communicate only with documented WHOOP endpoints
- Provide explicit API revocation and complete local-data deletion
- Collect no analytics, telemetry, crash reports, or user identifiers
- Treat API payloads, database exports, screenshots, and logs as sensitive health data

## Initial roadmap

- [x] Implement the POC access-token provisioning bundle and recovery API call
- [ ] Validate the POC end to end against a live WHOOP account
- [ ] Define and threat-model a future versioned provisioning bundle with refresh support
- [ ] Complete live local OAuth token exchange and QR provisioning
- [x] Implement encrypted access-token storage for the POC
- [x] Implement the current read-only recovery API client with fixture and model tests
- [ ] Implement refresh-token storage and serialized token refresh
- [ ] Build the Today screen for recovery, strain, and sleep
- [ ] Add recent sleep, cycle, recovery, and workout history
- [ ] Add Room caching, manual refresh, and periodic synchronization
- [ ] Add disconnect, access revocation, and complete local-data deletion
- [ ] Test offline behavior, token expiry, interrupted refresh, and API rate limiting
- [ ] Verify the controlled Light builder and prepare submission documentation

## Open questions

- Are there unpublished restrictions on using a client secret from an individually provisioned native application?
- What API update latency should users expect after the official app synchronizes a strap?
- Which privacy and review documents will Light require for a tool displaying health data?
- Should the setup helper support only macOS initially, or be cross-platform from its first release?

## References

- [WHOOP Developer documentation](https://developer.whoop.com/)
- [WHOOP OAuth documentation](https://developer.whoop.com/docs/developing/oauth/)
- [WHOOP API reference](https://developer.whoop.com/api/)
- [Light SDK](https://github.com/lightphone/light-sdk)
- [NOOP](https://github.com/ryanbr/noop), a separate direct-Bluetooth implementation and research reference
