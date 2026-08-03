#!/usr/bin/env python3
"""Authorize a WHOOP user locally and create a Whoof provisioning QR code."""

from __future__ import annotations

import argparse
import getpass
import hmac
import http.server
import json
import os
from pathlib import Path
import re
import secrets
import tempfile
import time
from typing import Callable, NamedTuple
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, urlopen
import webbrowser

AUTHORIZATION_URL = "https://api.prod.whoop.com/oauth/oauth2/auth"
TOKEN_URL = "https://api.prod.whoop.com/oauth/oauth2/token"
USER_AGENT = "Whoof/0.1 (+https://github.com/joshkarlin/whoof)"
REDIRECT_HOST = "127.0.0.1"
REDIRECT_PORT = 8765
REDIRECT_PATH = "/callback"
REDIRECT_URI = f"http://{REDIRECT_HOST}:{REDIRECT_PORT}{REDIRECT_PATH}"
OAUTH_SCOPE = "read:recovery read:cycles read:sleep read:workout offline"
CALLBACK_TIMEOUT_SECONDS = 300
HTTP_REQUEST_TIMEOUT_SECONDS = 30
STATE_BYTES = 32

MAX_PAYLOAD_LENGTH = 2_900
MIN_TOKEN_LENGTH = 20
MAX_OAUTH_ERROR_BODY_BYTES = 8_192
MAX_OAUTH_ERROR_CODE_LENGTH = 64
MAX_OAUTH_ERROR_DESCRIPTION_LENGTH = 240
SAFE_OAUTH_ERROR_CODE = re.compile(r"^[A-Za-z0-9._:-]+$")
SENSITIVE_ERROR_DESCRIPTION = re.compile(
    r"(?i)\b(?:access[_ -]?token|refresh[_ -]?token|client[_ -]?secret|"
    r"authorization[_ -]?code)\b\s*(?:[:=]\s*|is\s+)[^\s,;]+"
)


class OAuthError(Exception):
    """A user-safe OAuth failure that does not contain credential material."""


class OAuthTokens(NamedTuple):
    """Validated token response returned by WHOOP."""

    access_token: str
    refresh_token: str
    expires_in: int


class OAuthCredentials(NamedTuple):
    """Validated tokens together with the caller's WHOOP application credentials."""

    client_id: str
    client_secret: str
    access_token: str
    refresh_token: str
    expires_in: int


def _validate_oauth_token(token: str, label: str) -> str:
    """Return a normalized token without including credential material in errors."""
    if not isinstance(token, str):
        raise ValueError(f"{label} is missing or malformed.")
    normalized = token.strip()
    if len(normalized) < MIN_TOKEN_LENGTH or any(character.isspace() for character in normalized):
        raise ValueError(f"{label} is missing or malformed.")
    return normalized


def validate_access_token(token: str) -> str:
    return _validate_oauth_token(token, "Access token")


def validate_refresh_token(token: str) -> str:
    return _validate_oauth_token(token, "Refresh token")


def validate_expires_in(expires_in: object) -> int:
    """Return a strictly positive integer token lifetime."""
    if isinstance(expires_in, bool) or not isinstance(expires_in, int) or expires_in <= 0:
        raise ValueError("Token expiry is missing or malformed.")
    return expires_in


def build_provisioning_payload(token: str) -> str:
    validated = validate_access_token(token)
    payload = "whoof://provision?" + urlencode({"v": "1", "access_token": validated})
    if len(payload) > MAX_PAYLOAD_LENGTH:
        raise ValueError("Access token is too large for a Whoof provisioning QR code.")
    return payload


def build_provisioning_payload_v2(
    client_id: str,
    client_secret: str,
    access_token: str,
    refresh_token: str,
    expires_in: int,
) -> str:
    """Build a v2 bundle containing the complete validated OAuth credentials."""
    validated_client_id = validate_client_credential(client_id, "Client ID")
    validated_client_secret = validate_client_credential(client_secret, "Client secret")
    validated_access_token = validate_access_token(access_token)
    validated_refresh_token = validate_refresh_token(refresh_token)
    validated_expires_in = validate_expires_in(expires_in)
    payload = "whoof://provision?" + urlencode(
        {
            "v": "2",
            "client_id": validated_client_id,
            "client_secret": validated_client_secret,
            "access_token": validated_access_token,
            "refresh_token": validated_refresh_token,
            "expires_in": str(validated_expires_in),
        }
    )
    if len(payload) > MAX_PAYLOAD_LENGTH:
        raise ValueError("OAuth credentials are too large for a Whoof provisioning QR code.")
    return payload


def write_provisioning_qr(
    access_token: str,
    output: Path,
    *,
    refresh_token: str | None = None,
    expires_in: int | None = None,
    client_id: str | None = None,
    client_secret: str | None = None,
    overwrite: bool = False,
) -> None:
    """Write a local, mode-600 PNG containing a versioned provisioning payload."""
    output = output.expanduser()
    if not output.parent.is_dir():
        raise FileNotFoundError(f"Output directory does not exist: {output.parent}")
    if output.exists() and not overwrite:
        raise FileExistsError(f"Refusing to overwrite existing file: {output}")

    import qrcode

    qr = qrcode.QRCode(
        error_correction=qrcode.constants.ERROR_CORRECT_L,
        box_size=8,
        border=4,
    )
    v2_fields = (client_id, client_secret, refresh_token, expires_in)
    if all(field is None for field in v2_fields):
        payload = build_provisioning_payload(access_token)
    elif any(field is None for field in v2_fields):
        raise ValueError("Complete OAuth credentials are required for a v2 provisioning QR code.")
    else:
        payload = build_provisioning_payload_v2(
            client_id,
            client_secret,
            access_token,
            refresh_token,
            expires_in,
        )
    qr.add_data(payload)
    qr.make(fit=True)

    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            dir=output.parent,
            prefix=f".{output.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)

        qr.make_image(fill_color="black", back_color="white").save(
            temporary_path,
            format="PNG",
        )
        os.chmod(temporary_path, 0o600)

        if overwrite:
            os.replace(temporary_path, output)
        else:
            # Linking the completed temporary file makes the no-overwrite case atomic.
            os.link(temporary_path, output)
            os.unlink(temporary_path)
        temporary_path = None
        os.chmod(output, 0o600)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def validate_client_credential(value: str, label: str) -> str:
    if not isinstance(value, str):
        raise ValueError(f"{label} is missing or malformed.")
    normalized = value.strip()
    if not normalized or any(not 0x21 <= ord(character) <= 0x7E for character in normalized):
        raise ValueError(f"{label} is missing or malformed.")
    return normalized


def generate_state() -> str:
    """Generate a state value with enough entropy for a single authorization attempt."""
    return secrets.token_hex(STATE_BYTES)


def build_authorization_url(client_id: str, state: str) -> str:
    client_id = validate_client_credential(client_id, "Client ID")
    if not state:
        raise ValueError("OAuth state is missing.")
    query = urlencode(
        {
            "response_type": "code",
            "client_id": client_id,
            "redirect_uri": REDIRECT_URI,
            "scope": OAUTH_SCOPE,
            "state": state,
        }
    )
    return f"{AUTHORIZATION_URL}?{query}"


def parse_callback_target(target: str, expected_state: str) -> str:
    """Validate the exact callback path, OAuth result, and CSRF state."""
    parsed = urlsplit(target)
    if parsed.path != REDIRECT_PATH or parsed.fragment:
        raise OAuthError("WHOOP returned to an unexpected callback.")

    try:
        values = parse_qs(
            parsed.query,
            keep_blank_values=True,
            strict_parsing=True,
        )
    except ValueError as error:
        raise OAuthError("WHOOP returned an invalid authorization response.") from error

    if len(values.get("state", [])) != 1:
        raise OAuthError("WHOOP returned an invalid authorization state.")
    returned_state = values["state"][0]
    if not returned_state or not hmac.compare_digest(returned_state, expected_state):
        raise OAuthError("WHOOP authorization state did not match.")

    if "error" in values or "error_description" in values:
        raise OAuthError("WHOOP authorization was denied or failed.")
    if len(values.get("code", [])) != 1 or not values["code"][0]:
        raise OAuthError("WHOOP did not return an authorization code.")
    return values["code"][0]


def _sanitize_oauth_error_description(
    description: str,
    sensitive_values: tuple[str, ...],
) -> str:
    """Keep a short, single-line description without credential material."""
    description = SENSITIVE_ERROR_DESCRIPTION.sub("[redacted]", description)
    for sensitive_value in sensitive_values:
        if sensitive_value:
            description = description.replace(sensitive_value, "[redacted]")
    description = " ".join(description.split())
    description = "".join(
        character for character in description if character.isprintable()
    )
    return description[:MAX_OAUTH_ERROR_DESCRIPTION_LENGTH].rstrip()


def _oauth_http_error_message(
    error: HTTPError,
    *,
    sensitive_values: tuple[str, ...],
) -> str:
    """Build a bounded diagnostic from the documented OAuth JSON fields."""
    status_message = f"WHOOP token exchange failed (HTTP {error.code})."
    try:
        body = error.read(MAX_OAUTH_ERROR_BODY_BYTES + 1)
    except (OSError, ValueError):
        return status_message
    if len(body) > MAX_OAUTH_ERROR_BODY_BYTES:
        return status_message
    try:
        payload = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return status_message
    if not isinstance(payload, dict):
        return status_message

    parts: list[str] = []
    error_code = payload.get("error")
    if (
        isinstance(error_code, str)
        and len(error_code) <= MAX_OAUTH_ERROR_CODE_LENGTH
        and SAFE_OAUTH_ERROR_CODE.fullmatch(error_code)
    ):
        parts.append(error_code)

    description = payload.get("error_description")
    if isinstance(description, str):
        safe_description = _sanitize_oauth_error_description(
            description,
            sensitive_values,
        )
        if safe_description:
            parts.append(safe_description)

    if parts:
        return f"{status_message[:-1]}: {' - '.join(parts)}"
    return status_message


def parse_oauth_token_response(response_body: bytes | str) -> OAuthTokens:
    """Parse and strictly validate the token response fields used by Whoof."""
    try:
        token_response = json.loads(response_body)
    except (TypeError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise OAuthError("WHOOP returned an invalid token response.") from error
    if not isinstance(token_response, dict):
        raise OAuthError("WHOOP returned an invalid token response.")

    access_token = token_response.get("access_token")
    if not isinstance(access_token, str):
        raise OAuthError("WHOOP did not return an access token.")
    try:
        validated_access_token = validate_access_token(access_token)
    except ValueError as error:
        raise OAuthError("WHOOP returned an invalid access token.") from error

    refresh_token = token_response.get("refresh_token")
    if not isinstance(refresh_token, str):
        raise OAuthError("WHOOP did not return a refresh token.")
    try:
        validated_refresh_token = validate_refresh_token(refresh_token)
    except ValueError as error:
        raise OAuthError("WHOOP returned an invalid refresh token.") from error

    try:
        validated_expires_in = validate_expires_in(token_response.get("expires_in"))
    except ValueError as error:
        raise OAuthError("WHOOP returned an invalid token expiry.") from error

    return OAuthTokens(
        access_token=validated_access_token,
        refresh_token=validated_refresh_token,
        expires_in=validated_expires_in,
    )


class _CallbackServer(http.server.HTTPServer):
    allow_reuse_address = False


def wait_for_callback(
    expected_state: str,
    *,
    timeout_seconds: int = CALLBACK_TIMEOUT_SECONDS,
    on_ready: Callable[[], None] | None = None,
) -> str:
    """Bind the loopback callback and wait for one validated OAuth response."""

    result: dict[str, str | OAuthError] = {}

    class CallbackHandler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
            if self.client_address[0] != REDIRECT_HOST:
                result["error"] = OAuthError("The OAuth callback must come from this computer.")
                self._respond(400, "Authorization callback rejected.")
                return
            try:
                result["code"] = parse_callback_target(self.path, expected_state)
            except OAuthError as error:
                result["error"] = error
                self._respond(400, "Authorization could not be completed.")
            else:
                self._respond(200, "Authorization received. Return to the terminal.")

        def _respond(self, status: int, body: str) -> None:
            encoded = body.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(encoded)

        def log_message(self, format: str, *args: object) -> None:
            # The request target can contain an authorization code or state.
            return

    try:
        server = _CallbackServer((REDIRECT_HOST, REDIRECT_PORT), CallbackHandler)
    except OSError as error:
        raise OAuthError("Could not bind the local OAuth callback port.") from error

    deadline = time.monotonic() + timeout_seconds
    try:
        if on_ready is not None:
            on_ready()
        while not result:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise OAuthError("Timed out waiting for WHOOP authorization.")
            server.timeout = min(1.0, remaining)
            server.handle_request()
    finally:
        server.server_close()

    if "error" in result:
        raise result["error"]
    return result["code"]


def exchange_authorization_code(
    code: str,
    client_id: str,
    client_secret: str,
) -> OAuthCredentials:
    """Exchange a one-time code for validated OAuth credentials."""
    validated_client_id = validate_client_credential(client_id, "Client ID")
    validated_client_secret = validate_client_credential(client_secret, "Client secret")
    form = urlencode(
        {
            "grant_type": "authorization_code",
            "code": code,
            "client_id": validated_client_id,
            "client_secret": validated_client_secret,
            "redirect_uri": REDIRECT_URI,
        }
    ).encode("ascii")
    request = Request(
        TOKEN_URL,
        data=form,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded",
            "User-Agent": USER_AGENT,
        },
        method="POST",
    )
    try:
        with urlopen(request, timeout=HTTP_REQUEST_TIMEOUT_SECONDS) as response:
            response_body = response.read()
    except HTTPError as error:
        raise OAuthError(
            _oauth_http_error_message(
                error,
                sensitive_values=(code, validated_client_id, validated_client_secret),
            )
        ) from error
    except URLError as error:
        raise OAuthError("WHOOP token exchange could not reach the service.") from error
    except OSError as error:
        raise OAuthError("WHOOP token exchange failed due to a local network error.") from error

    tokens = parse_oauth_token_response(response_body)
    return OAuthCredentials(
        client_id=validated_client_id,
        client_secret=validated_client_secret,
        access_token=tokens.access_token,
        refresh_token=tokens.refresh_token,
        expires_in=tokens.expires_in,
    )


def authorize_and_exchange(
    client_id: str,
    client_secret: str,
    *,
    browser_open: Callable[[str], bool] = webbrowser.open,
    callback_waiter: Callable[..., str] = wait_for_callback,
    token_exchanger: Callable[[str, str, str], OAuthCredentials] = exchange_authorization_code,
) -> OAuthCredentials:
    """Run one OAuth attempt without persisting client credentials or token data."""
    client_id = validate_client_credential(client_id, "Client ID")
    client_secret = validate_client_credential(client_secret, "Client secret")
    state = generate_state()
    authorization_url = build_authorization_url(client_id, state)

    def open_browser() -> None:
        if not browser_open(authorization_url):
            raise OAuthError("Could not open the WHOOP authorization page in a browser.")

    code = callback_waiter(
        state,
        timeout_seconds=CALLBACK_TIMEOUT_SECONDS,
        on_ready=open_browser,
    )
    return token_exchanger(code, client_id, client_secret)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Authorize WHOOP locally and generate a Whoof provisioning QR code."
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("whoof-provision.png"),
        help="QR image destination (default: whoof-provision.png)",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Replace an existing output image explicitly",
    )
    args = parser.parse_args()

    try:
        client_id = input("WHOOP client ID: ")
        client_secret = getpass.getpass("WHOOP client secret (input hidden): ")
        tokens = authorize_and_exchange(client_id, client_secret)
        write_provisioning_qr(
            tokens.access_token,
            args.output,
            client_id=tokens.client_id,
            client_secret=tokens.client_secret,
            refresh_token=tokens.refresh_token,
            expires_in=tokens.expires_in,
            overwrite=args.overwrite,
        )
    except (EOFError, KeyboardInterrupt):
        raise SystemExit("Authorization cancelled.") from None
    except (FileExistsError, FileNotFoundError, ValueError, OSError, OAuthError) as error:
        raise SystemExit(str(error)) from None

    print(f"Wrote {args.output.expanduser()}")
    print("Keep the QR code private and delete it after provisioning Whoof.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
