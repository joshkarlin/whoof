# Whoof Privacy Policy

Last updated: November 1, 2026

Whoof is an unofficial, read-only WHOOP companion for the Light Phone III. It is intended for
developers and technically capable users. Whoof is not affiliated with or endorsed by WHOOP or
Light.

## Data Whoof processes

Whoof processes:

- WHOOP OAuth credentials that the user provisions by scanning a locally generated QR code: the
  user's own WHOOP Developer client ID and secret, an access token, and a rotating refresh token.
- Recovery, cycle (day strain), sleep, and workout records returned for that user by the official
  WHOOP Developer API, including scores, heart-rate values, sleep-stage totals, and workout
  heart-rate zone durations.

Whoof requests the `read:recovery`, `read:cycles`, `read:sleep`, `read:workout`, and `offline`
OAuth scopes. It does not request profile or body-measurement access.

## How data is used

Whoof uses the credentials only to request the user's records directly from the official WHOOP API
and display them on the Light Phone. Whoof does not use WHOOP data for advertising,
profiling, medical advice, or any unrelated purpose.

Whoof is a general-wellness tool, not a medical device. Its output must not be used to make medical
decisions.

## Storage and retention

Credentials are stored only on the user's Light Phone, encrypted with an AES-GCM key held by Android
Keystore.

WHOOP records from the last 30 days are cached in a local database on the Light Phone so the
application remains useful offline. Records older than 30 days are deleted at the next successful
synchronization. The time of the last successful synchronization is stored as a nonsensitive
preference. The cache is not encrypted separately from Android's application-private storage.

Disconnecting in Whoof (Settings, then "Disconnect and delete data") deletes the stored credentials,
the cached WHOOP records, and the synchronization time. Uninstalling Whoof deletes its local
application data. Access already granted to the WHOOP application may also be revoked through the
user's WHOOP account or by deleting the application in the WHOOP Developer Dashboard.

## Data sharing and network services

Whoof sends credentials only to WHOOP over HTTPS as authorization for requests to the official
WHOOP Developer API. It does not operate or use a developer-controlled backend, data relay,
analytics service, advertising service, telemetry service, or crash-reporting service.

Data remains subject to WHOOP's own terms and privacy practices while it is held or processed by
WHOOP.

## Permissions

Whoof requests:

- Camera access, used only to scan the local provisioning QR code.
- Internet and network-state access, used to communicate directly with the WHOOP API.

Whoof does not record or upload camera images. QR scanning occurs on the device.

## Security

Users must keep provisioning QR codes and access tokens private. Anyone possessing a valid access
token may be able to access the WHOOP data authorized by that token. Generated provisioning images
should be deleted immediately after setup.

## Changes to this policy

This policy will be updated before Whoof collects additional categories of data, broadens its OAuth
scopes, adds persistent health-data storage, or changes how data is transmitted or shared. The
revision date above identifies the current version.

## Contact

For privacy questions or support, open an issue at
<https://github.com/joshkarlin/whoof/issues>. Do not include access tokens, provisioning QR codes,
WHOOP account details, or health data in a public issue.
