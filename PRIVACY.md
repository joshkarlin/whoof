# Whoof Privacy Policy

Last updated: November 1, 2026

Whoof is an unofficial WHOOP companion for the Light Phone III. It is intended for
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

An experimental, separate private-API backend is being prepared for app UI integration. When used,
it processes an account access/refresh session and a user-selected activity's sport, time window,
timezone, submission state, and receipt. Optional password and verification-code inputs are used
only for authentication and are not persisted. Private account credentials are distinct from
Developer OAuth and can grant broader account access; only provision them on a trusted device.

## How data is used

Whoof uses Developer OAuth credentials only to request the user's records directly from the official
WHOOP API and display them on the Light Phone. The experimental private integration authenticates
directly with WHOOP, verifies account access, and creates an activity only after explicit user
confirmation. It does not control or pair with the strap. Whoof does not use WHOOP data for advertising,
profiling, medical advice, or any unrelated purpose.

Whoof is a general-wellness tool, not a medical device. Its output must not be used to make medical
decisions.

## Storage and retention

Credentials are stored only on the user's Light Phone, encrypted with an AES-GCM key held by Android
Keystore. The experimental private backend stores its session and one draft/submission record in
a separate encrypted preference record.

WHOOP records from the last 30 days are cached in a local database on the Light Phone so the
application remains useful offline. Records older than 30 days are deleted at the next successful
synchronization. The time of the last successful synchronization is stored as a nonsensitive
preference. The cache is not encrypted separately from Android's application-private storage.

Disconnecting in Whoof (Settings, then "Disconnect and delete data") deletes the stored Developer
OAuth credentials, the cached WHOOP records, and the synchronization time. Private disconnect removes
its credentials but retains an attempted-submission marker to prevent accidental replay; explicit
private local-data deletion also removes that marker. These operations are independent. Uninstalling
Whoof deletes its local application data. Access already granted to the WHOOP application may also be
revoked through the
user's WHOOP account or by deleting the application in the WHOOP Developer Dashboard.

The optional local Python helpers run on the user's computer. They store a private account session
and activity drafts as owner-only plaintext files under the ignored `local/private-api/` directory,
and generate owner-only plaintext provisioning QR images. Android Keystore does not protect those
computer files. Delete QR images after scanning and delete local helper artifacts when no longer
needed. Local deletion is not server-side session revocation or deletion of a submitted activity.

## Data sharing and network services

Whoof sends credentials and requested data only to fixed WHOOP endpoints over HTTPS: the documented
Developer API/OAuth endpoints and, for the experimental private integration, WHOOP's researched
authentication, account-check, and activity-creation endpoints. It does not operate or use a developer-controlled backend, data relay,
analytics service, advertising service, telemetry service, or crash-reporting service.

Data remains subject to WHOOP's own terms and privacy practices while it is held or processed by
WHOOP.

## Permissions

Whoof requests:

- Camera access, used only to scan the local provisioning QR code.
- Internet and network-state access, used to communicate directly with the WHOOP API.

Whoof does not record or upload camera images. QR scanning occurs on the device.

## Security

Users must keep provisioning QR codes, developer client secrets, and account/OAuth sessions private.
Anyone possessing valid credentials may be able to access the authorized WHOOP data; private account
sessions may also permit account changes. Generated provisioning images
should be deleted immediately after setup.

## Changes to this policy

This policy will be updated before Whoof collects additional categories of data, broadens its OAuth
scopes, adds persistent health-data storage, or changes how data is transmitted or shared. The
revision date above identifies the current version.

## Contact

For privacy questions or support, open an issue at
<https://github.com/joshkarlin/whoof/issues>. Do not include access tokens, provisioning QR codes,
WHOOP account details, or health data in a public issue.
