# Whoof Privacy Policy

Last updated: August 2, 2026

Whoof is an unofficial, read-only WHOOP companion for the Light Phone III. It is intended for
developers and technically capable users. Whoof is not affiliated with or endorsed by WHOOP or
Light.

## Data Whoof processes

Whoof processes:

- A short-lived WHOOP OAuth access token that the user provisions by scanning a locally generated
  QR code.
- Recovery information returned for that user by the official WHOOP Developer API, including the
  recovery score and related values displayed by the application.

The current proof of concept requests only the `read:recovery` OAuth scope. It does not request an
`offline` scope or store a refresh token.

## How data is used

Whoof uses the access token only to request the user's recovery information directly from the
official WHOOP API and display it on the Light Phone. Whoof does not use WHOOP data for advertising,
profiling, medical advice, or any unrelated purpose.

Whoof is a general-wellness tool, not a medical device. Its output must not be used to make medical
decisions.

## Storage and retention

The access token is stored only on the user's Light Phone. It is encrypted with an AES-GCM key held
by Android Keystore. The current proof of concept keeps the latest recovery response in application
memory for display and does not persist recovery records to a local database.

Disconnecting in Whoof deletes the locally stored access token. Uninstalling Whoof deletes its local
application data. Access already granted to the WHOOP application may also be revoked through the
user's WHOOP account or by deleting the application in the WHOOP Developer Dashboard.

## Data sharing and network services

Whoof sends the access token only to WHOOP over HTTPS as authorization for requests to the official
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
