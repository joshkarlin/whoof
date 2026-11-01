# Whoof Privacy Policy

Last updated: November 1, 2026

Whoof is an unofficial WHOOP companion for the Light Phone III. It is intended for
developers and technically capable users. Whoof is not affiliated with or endorsed by WHOOP or
Light.

## Data Whoof processes

Whoof processes:

- The user's WHOOP account session: an access token, a rotating refresh token, and its expiry. The
  user provides it by signing in on the phone (email, password, and a verification code if WHOOP
  asks) or by scanning a locally generated account QR code. Passwords and verification codes are used
  only to authenticate and are not persisted.
- Recovery, cycle (day strain), sleep, and workout records returned for that account by WHOOP,
  including scores, heart-rate values, sleep-stage totals, and workout heart-rate zone durations.
- For activity logging, a user-selected activity's sport, time window, timezone, submission state,
  and receipt, plus a running activity timer and recently used sports.

Whoof does not use WHOOP Developer OAuth credentials, client IDs, or client secrets. The account
session is broader than a Developer API token and can permit account changes; only sign in on a
trusted device.

## How data is used

Whoof uses the account session only to request the user's records directly from WHOOP, display them
on the Light Phone, and, after explicit user confirmation, create or delete an activity. It does not control or pair with the strap. Whoof does not use WHOOP data for advertising,
profiling, medical advice, or any unrelated purpose.

Whoof is a general-wellness tool, not a medical device. Its output must not be used to make medical
decisions.

## Storage and retention

The session is stored only on the user's Light Phone, encrypted with an AES-GCM key held by Android
Keystore, together with one activity draft/submission record in the same encrypted preference record.

WHOOP records from the last 30 days are cached in a local database on the Light Phone so the
application remains useful offline. Records older than 30 days are deleted at the next successful
synchronization. The time of the last successful synchronization is stored as a nonsensitive
preference. The cache is not encrypted separately from Android's application-private storage.

Signing out in Whoof (Settings, then "WHOOP account", then SIGN OUT) deletes the stored session, the
cached WHOOP records, the synchronization time, the activity timer, and recent sports, but retains an
attempted-submission marker, if one exists, to prevent accidental replay. Settings, then "Delete
local data" removes all of that including the marker. Neither revokes the session on WHOOP's servers;
changing the WHOOP password or ending other sessions in WHOOP does. Uninstalling Whoof deletes its
local application data.

The optional local Python helpers run on the user's computer. They store a private account session
and activity drafts as owner-only plaintext files under the ignored `local/private-api/` directory,
and generate owner-only plaintext provisioning QR images. Android Keystore does not protect those
computer files. Delete QR images after scanning and delete local helper artifacts when no longer
needed. Local deletion is not server-side session revocation or deletion of a submitted activity.

## Data sharing and network services

Whoof sends credentials and requested data only to fixed WHOOP endpoints over HTTPS: WHOOP's
authentication endpoint, its account-check, activity-creation and deletion endpoints, and its
`/developer/v2/` read endpoints, which Whoof calls with the account session. It does not operate or use a developer-controlled backend, data relay,
analytics service, advertising service, telemetry service, or crash-reporting service.

Data remains subject to WHOOP's own terms and privacy practices while it is held or processed by
WHOOP.

## Permissions

Whoof requests:

- Camera access, used only to scan the local account QR code.
- Internet and network-state access, used to communicate directly with the WHOOP API.

Whoof does not record or upload camera images. QR scanning occurs on the device.

## Security

Users must keep account QR codes and sessions private. Anyone possessing a valid session may be
able to access WHOOP data and change the account. Generated QR images should be deleted
immediately after setup.

## Changes to this policy

This policy will be updated before Whoof collects additional categories of data, reads additional
WHOOP endpoints, adds persistent health-data storage, or changes how data is transmitted or shared. The
revision date above identifies the current version.

## Contact

For privacy questions or support, open an issue at
<https://github.com/joshkarlin/whoof/issues>. Do not include access tokens, account QR codes,
WHOOP account details, or health data in a public issue.
