# WHOOP Private API PoC

This is a local CLI proof of concept for creating a WHOOP activity record. It is not part of the installed app, which does not currently support private-account credentials. Browser-session clipboard import and account verification have succeeded on the live account. One user-selected real activity was submitted once and read back through the WHOOP API, confirming the activity ID, type, start/end times, and `SCORED` status.

## Before starting

Use your WHOOP account email/password and complete MFA if prompted. These are separate from the public developer OAuth/QR credentials; do not substitute those credentials. No band pairing, relay, or companion app is required to create an activity record. The official WHOOP phone app still syncs the band and its metrics.

The CLI stores its session and activity drafts under `local/private-api/`, within the ignored `local/` directory. Files are owner-only. Tokens are stored as plaintext on this computer, not in the OS Keystore. Email, password, and MFA input use hidden terminal prompts; passwords and MFA codes are not persisted. Protect the local account session and do not share or commit this directory.

## Login and read-only check

Direct password sign-in is currently rejected by WHOOP for the tested account before any session is issued (`401 / NotAuthorizedException`), despite successful browser sign-in. Changing client IDs and HTTP transports did not resolve it; the cause is not confirmed. The helper now reports the failing stage and a recognized error code without exposing WHOOP response text. Avoid repeating password attempts.

```sh
uv run --frozen python setup/private_api_poc.py login
uv run --frozen python setup/private_api_poc.py check
```

`login` prompts for account credentials and MFA when needed. The initial authenticated check calls `GET /users-service/v2/bootstrap?apiVersion=7`; it reads account/bootstrap data and does not write an activity. Cognito sessions have a configured, fixed expiry. Do not assume refresh makes a session indefinite or promise an exact 30-day lifetime; sign in again when the session expires.

`check --refresh` tests session refresh followed by the same account read. Neither command submits activities. `local/private-api/check.json` records only the check result and timestamp, so its contents can be inspected without reading credential files. This CLI currently targets macOS/Linux and uses a local process lock to prevent overlapping session operations.

### Import a browser session

This fallback uses the WHOOP login that already works in your browser. Browser-session clipboard import and account verification have been confirmed on the live account. Private-session refresh remains pending live validation.

1. Open `https://app.whoop.com` in your browser and sign in normally.
2. Print the session-copy snippet locally:

   ```sh
   uv run --frozen python setup/private_api_poc.py browser-snippet
   ```

3. Open Developer Tools > Console on the signed-in WHOOP page and run the printed snippet. It copies only the `whoop-auth-token` and `whoop-auth-refresh-token` cookies into your local clipboard. It makes no network request and does not print their values or send them to another site. If browser policy prevents access to those cookies, the snippet stops.
4. Return to your terminal and run:

   ```sh
   uv run --frozen python setup/private_api_poc.py login --browser-clipboard
   ```

5. On macOS, type that command after copying the session. Copying the command itself would replace the session in your clipboard. The helper reads the clipboard directly, without echoing or requiring a terminal paste, and checks one authenticated account read before saving the session. It reports when the session format is accepted and the network check starts. Clear the clipboard afterward, for example with `copy('')` in the browser console.

The older `login --browser-session` option still accepts hidden manual input, but long session bundles can exceed a terminal's canonical line buffer. An isolated macOS pseudoterminal test reproduced the reported hang at 3,000 characters with a 1,024-character buffer. Use `--browser-clipboard` on macOS; do not paste the session into a shell command, chat, or an ordinary visible prompt.

JWT expiry is parsed only to determine local refresh timing; WHOOP's authenticated response, not the unverified JWT claims, establishes whether the session is accepted. Browser sessions use the same separate local credential file and do not change the installed app's OAuth state.

## Preview and submit

Choose an activity only after login/check succeeds. Supported sport values are `running`, `cycling`, `walking`, `hiking`, and `weightlifting`. Supply ISO-8601 start and end times with explicit timezone offsets, and an IANA timezone for the activity:

```sh
uv run --frozen python setup/private_api_poc.py preview \
  --sport walking \
  --start '2026-10-27T10:00:00+00:00' \
  --end '2026-10-27T10:30:00+00:00' \
  --timezone Europe/London
```

Review the preview and retain its draft ID. Submission defaults to preview-only; sending requires the explicit `--confirm` flag:

```sh
uv run --frozen python setup/private_api_poc.py submit <draft-id>
uv run --frozen python setup/private_api_poc.py submit <draft-id> --confirm
```

At most one request is sent for a draft. A timeout, process crash, or server error can leave the result uncertain; the CLI must not retry automatically. Check WHOOP for the activity before preparing another draft, so an accepted request is not duplicated.

## References and validation

Four additional read-only endpoints have also been checked against the live account. See [read tests and schema notes](private-api-read-tests.md) for the observed capabilities, repeatable probe, and integration limitations. These checks do not add private-API support to the installed app.

The PoC follows the pinned [Totem Cognito flow](https://github.com/thebriangao/totem/blob/f9ac7f18198ccc1ea3a21e4d6bdc83733b4ef702/src/whoop/cognito.ts) and [activity creation implementation](https://github.com/thebriangao/totem/blob/f9ac7f18198ccc1ea3a21e4d6bdc83733b4ef702/src/tools/v2/activity_create.ts). AWS documents that Cognito refresh-token lifetime is configurable and bounded, and that rotation does not extend the original validity window: [Cognito refresh tokens](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-refresh-token.html).

The browser cookie names are independently documented by [WHOOP HR bootstrap](https://github.com/jmilinovich/Scratch/blob/8d7f6284ccdda1c7d093259e9833512d4305cae5/src/whoop_hr/cli.py).

These references do not guarantee future private-API compatibility. Live login and one activity submission have now been verified; private-session refresh still requires live validation. Continue to select and explicitly authorize each real activity before submission, and verify the receipt without automatically retrying a write.
