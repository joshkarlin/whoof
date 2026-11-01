"""Experimental WHOOP account-session transport; independent of developer OAuth."""

from __future__ import annotations

import base64
from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import stat
import tempfile
import time
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

AUTH_URL = "https://api.prod.whoop.com/auth-service/v3/whoop/"
ACCOUNT_URL = "https://api.prod.whoop.com/users-service/v2/bootstrap?apiVersion=7"
ACTIVITY_URL = "https://api.prod.whoop.com/core-details-bff/v0/create-activity?apiVersion=7"
HTTP_TIMEOUT = 30
MAX_RESPONSE_BYTES = 1_048_576
AUTH_USER_AGENT = (
    "aws-sdk-swift/1.5.86 ua/2.1 api/cognito_identity_provider#1.5.86 "
    "os/ios#26.3.1 lang/swift#5.10 m/D,N,Z,b"
)
SPORTS = {"running": 0, "cycling": 1, "weightlifting": 45, "hiking": 52, "walking": 63}
MFA_FIELDS = {
    "SMS_MFA": "SMS_MFA_CODE",
    "SOFTWARE_TOKEN_MFA": "SOFTWARE_TOKEN_MFA_CODE",
    "EMAIL_OTP": "EMAIL_OTP_CODE",
}
AUTH_ERROR_CODES = frozenset({
    "NotAuthorizedException", "UserNotFoundException", "UserNotConfirmedException",
    "PasswordResetRequiredException", "CodeMismatchException", "ExpiredCodeException",
    "InvalidParameterException", "TooManyRequestsException",
})


class PrivateApiError(Exception):
    """Only fixed, user-safe messages may be passed to this exception."""

    def __init__(self, message: str, *, status: int | None = None, code: str | None = None):
        super().__init__(message)
        self.status = status
        self.code = code if code in AUTH_ERROR_CODES else None


class SubmissionUncertainError(PrivateApiError):
    """The server may have accepted a write. Retrying could create a duplicate."""


@dataclass(frozen=True)
class SessionTokens:
    access_token: str = field(repr=False)
    refresh_token: str = field(repr=False)
    expires_at: float


@dataclass(frozen=True)
class LoginChallenge:
    name: str
    session: str = field(repr=False)
    username: str = field(repr=False)


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward an account credential or bearer to a redirected endpoint.
        return None


def request_json(method: str, url: str, headers: dict, body: dict | None) -> dict:
    if (method, url) not in {
        ("POST", AUTH_URL), ("GET", ACCOUNT_URL), ("POST", ACTIVITY_URL),
    }:
        raise PrivateApiError("Unsupported WHOOP endpoint.")
    request = Request(
        url, data=json.dumps(body).encode("utf-8") if body is not None else None,
        headers=headers, method=method,
    )
    try:
        with build_opener(_NoRedirect()).open(request, timeout=HTTP_TIMEOUT) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except HTTPError as error:
        # WHOOP may echo submitted values in error bodies. Do not surface them.
        status = error.code
        try:
            details = json.loads(error.read(8192))
            error_code = details.get("__type") if isinstance(details, dict) else None
            if isinstance(error_code, str):
                error_code = error_code.rsplit("#", 1)[-1]
            else:
                error_code = None
        except (ValueError, UnicodeError, OSError):
            error_code = None
        error.close()
        messages = {
            400: "WHOOP rejected the request or verification code.",
            401: "WHOOP rejected this authenticated request (HTTP 401).",
            403: "WHOOP refused this private-API request (HTTP 403).",
            409: "WHOOP reported a conflict; check for an overlapping activity.",
            422: "WHOOP rejected the submitted activity fields.",
            429: "WHOOP rate limit reached. Wait before trying again.",
        }
        raise PrivateApiError(messages.get(status, "WHOOP returned an HTTP error."), status=status, code=error_code) from None
    except (URLError, OSError, TimeoutError):
        raise PrivateApiError("Unable to reach WHOOP; no automatic retry was made.") from None
    if len(raw) > MAX_RESPONSE_BYTES:
        raise PrivateApiError("WHOOP returned an oversized response.")
    try:
        result = json.loads(raw)
    except (ValueError, UnicodeError):
        raise PrivateApiError("WHOOP returned an invalid JSON response.") from None
    if not isinstance(result, dict):
        raise PrivateApiError("WHOOP returned an unexpected response shape.")
    return result


def _secret(value: object) -> str:
    if not isinstance(value, str) or not 20 <= len(value) <= 16_384:
        raise PrivateApiError("WHOOP returned a missing or malformed session credential.")
    if any(ord(character) < 33 or ord(character) > 126 for character in value):
        raise PrivateApiError("WHOOP returned a missing or malformed session credential.")
    return value


class PrivateWhoopClient:
    def __init__(self, transport: Callable = request_json, clock: Callable = time.time):
        self.transport = transport
        self.clock = clock

    def _auth(self, operation: str, payload: dict) -> dict:
        try:
            return self.transport("POST", AUTH_URL, {
                "Content-Type": "application/x-amz-json-1.1",
                "X-Amz-Target": f"AWSCognitoIdentityProviderService.{operation}",
                "User-Agent": AUTH_USER_AGENT,
                "Accept": "application/json",
                "amz-sdk-request": "attempt=1; max=1",
                "amz-sdk-invocation-id": str(uuid4()),
                "Accept-Language": "en-US,en;q=0.9",
            }, payload)
        except PrivateApiError as error:
            if error.status is None:
                raise
            stage = "verification" if operation == "RespondToAuthChallenge" else (
                "session refresh" if payload.get("AuthFlow") == "REFRESH_TOKEN_AUTH" else "sign-in"
            )
            detail = f"; {error.code}" if error.code else ""
            message = f"WHOOP {stage} was rejected (HTTP {error.status}{detail})."
            if stage == "sign-in" and error.status == 401:
                message += " This does not establish that the password is wrong. Use login --browser-session if browser sign-in works."
            raise PrivateApiError(message, status=error.status, code=error.code) from None

    def _tokens(self, response: dict, fallback_refresh: str | None = None) -> SessionTokens:
        result = response.get("AuthenticationResult")
        if not isinstance(result, dict):
            raise PrivateApiError("WHOOP did not return an authenticated session.")
        lifetime = result.get("ExpiresIn")
        if isinstance(lifetime, bool) or not isinstance(lifetime, int) or lifetime <= 0:
            raise PrivateApiError("WHOOP returned an invalid session lifetime.")
        return SessionTokens(
            _secret(result.get("AccessToken")),
            _secret(result.get("RefreshToken", fallback_refresh)),
            self.clock() + lifetime,
        )

    def _login_result(self, response: dict, username: str) -> SessionTokens | LoginChallenge:
        if "AuthenticationResult" in response:
            return self._tokens(response)
        name = response.get("ChallengeName")
        session = response.get("Session")
        if name not in MFA_FIELDS or not isinstance(session, str) or not session:
            raise PrivateApiError("WHOOP requested an unsupported login challenge; no credentials were saved.")
        parameters = response.get("ChallengeParameters")
        if isinstance(parameters, dict):
            username = parameters.get("USERNAME") or parameters.get("USER_ID_FOR_SRP") or username
        if not isinstance(username, str) or not username:
            raise PrivateApiError("WHOOP returned an invalid login challenge.")
        return LoginChallenge(name, session, username)

    def begin_login(self, email: str, password: str) -> SessionTokens | LoginChallenge:
        if not email.strip() or not password:
            raise PrivateApiError("Email and password are required.")
        response = self._auth("InitiateAuth", {
            "AuthFlow": "USER_PASSWORD_AUTH", "ClientId": "",
            "AuthParameters": {"USERNAME": email.strip(), "PASSWORD": password},
        })
        return self._login_result(response, email.strip())

    def complete_login(self, challenge: LoginChallenge, code: str) -> SessionTokens | LoginChallenge:
        if challenge.name not in MFA_FIELDS or not code.strip():
            raise PrivateApiError("A supported verification challenge and code are required.")
        response = self._auth("RespondToAuthChallenge", {
            "ClientId": "", "ChallengeName": challenge.name, "Session": challenge.session,
            "ChallengeResponses": {
                "USERNAME": challenge.username, MFA_FIELDS[challenge.name]: code.strip(),
            },
        })
        return self._login_result(response, challenge.username)

    def refresh(self, tokens: SessionTokens) -> SessionTokens:
        response = self._auth("InitiateAuth", {
            "AuthFlow": "REFRESH_TOKEN_AUTH", "ClientId": "",
            "AuthParameters": {"REFRESH_TOKEN": tokens.refresh_token},
        })
        # Cognito REFRESH_TOKEN_AUTH normally retains the original refresh token.
        return self._tokens(response, tokens.refresh_token)

    @staticmethod
    def _headers(tokens: SessionTokens, time_zone: str = "Europe/London") -> dict:
        return {
            "Authorization": f"Bearer {tokens.access_token}", "Content-Type": "application/json",
            "Accept": "application/json", "User-Agent": "iOS",
            "x-whoop-device-platform": "iOS", "x-whoop-time-zone": time_zone,
            "x-whoop-bundle-name": "com.whoop.iphone",
            "x-whoop-ios-version": "5.48.0", "x-whoop-ios-build-number": "573097",
        }

    def check_account(self, tokens: SessionTokens) -> None:
        result = self.transport("GET", ACCOUNT_URL, self._headers(tokens), None)
        user = result.get("user")
        user_id = user.get("id") if isinstance(user, dict) else None
        if isinstance(user_id, bool) or not isinstance(user_id, (str, int)) or not str(user_id).isdigit():
            raise PrivateApiError("WHOOP did not return a recognizable authenticated account.")

    def create_activity(self, tokens: SessionTokens, payload: dict, time_zone: str) -> str:
        try:
            result = self.transport("POST", ACTIVITY_URL, self._headers(tokens, time_zone), payload)
        except PrivateApiError as error:
            if error.status is not None and 400 <= error.status < 500:
                raise
            raise SubmissionUncertainError(
                "Activity outcome is uncertain. Check WHOOP before creating another draft."
            ) from None
        activity_id = result.get("id")
        try:
            UUID(activity_id)
        except (ValueError, TypeError, AttributeError):
            raise SubmissionUncertainError(
                "WHOOP accepted the request but returned no recognizable receipt. Check WHOOP before resubmitting."
            ) from None
        return activity_id


def private_directory(path: Path) -> None:
    if path.is_symlink():
        raise PrivateApiError("The private session directory must not be a symbolic link.")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(path, 0o700)


def write_private_json(path: Path, value: dict) -> None:
    private_directory(path.parent)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, encoding="utf-8", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(value, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def read_private_json(path: Path) -> dict:
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, "r", encoding="utf-8") as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) & 0o077 or info.st_size > MAX_RESPONSE_BYTES:
                raise PrivateApiError("Local session or draft must be an owner-only regular file.")
            result = json.load(stream)
    except (OSError, ValueError, UnicodeError):
        raise PrivateApiError("Unable to read local session or draft. Log in or prepare a new draft.") from None
    if not isinstance(result, dict):
        raise PrivateApiError("Invalid local session or draft.")
    return result


def save_session(path: Path, tokens: SessionTokens) -> None:
    write_private_json(path, {
        "version": 1, "access_token": tokens.access_token, "refresh_token": tokens.refresh_token,
        "expires_at": tokens.expires_at,
    })


def load_session(path: Path) -> SessionTokens:
    data = read_private_json(path)
    expiry = data.get("expires_at")
    if data.get("version") != 1 or isinstance(expiry, bool) or not isinstance(expiry, (int, float)) or not math.isfinite(expiry) or expiry <= 0:
        raise PrivateApiError("Invalid local account session. Log in again.")
    return SessionTokens(_secret(data.get("access_token")), _secret(data.get("refresh_token")), float(expiry))


def parse_browser_session(payload: str, clock: Callable = time.time) -> SessionTokens:
    """Read session cookies supplied by the user; a server read must validate them."""
    try:
        if not isinstance(payload, str) or len(payload) > 65_536:
            raise ValueError
        data = json.loads(payload)
        if not isinstance(data, dict) or set(data) != {"access_token", "refresh_token"}:
            raise ValueError
        access = _secret(data["access_token"])
        refresh = _secret(data["refresh_token"])
        parts = access.split(".")
        if len(parts) != 3:
            raise ValueError
        claims = json.loads(base64.b64decode(parts[1] + "=" * (-len(parts[1]) % 4), altchars=b"-_", validate=True))
        expiry = claims.get("exp") if isinstance(claims, dict) else None
        if isinstance(expiry, bool) or not isinstance(expiry, (int, float)) or not math.isfinite(expiry) or expiry <= clock() + 60:
            raise ValueError
        # This is expiry metadata, not signature verification. check_account verifies acceptance.
        return SessionTokens(access, refresh, float(expiry))
    except (ValueError, TypeError, UnicodeError, PrivateApiError):
        raise PrivateApiError("Invalid or expired browser session. Copy a fresh session from the signed-in WHOOP page.") from None


def build_activity(sport: str, start: str, end: str, time_zone: str, *, now: datetime | None = None) -> dict:
    if sport not in SPORTS:
        raise PrivateApiError("Choose one of the supported sports.")
    try:
        zone = ZoneInfo(time_zone)
        start_time = datetime.fromisoformat(start.replace("Z", "+00:00"))
        end_time = datetime.fromisoformat(end.replace("Z", "+00:00"))
    except (ValueError, ZoneInfoNotFoundError):
        raise PrivateApiError("Use ISO timestamps with UTC offsets and a valid IANA timezone.") from None
    if start_time.tzinfo is None or end_time.tzinfo is None:
        raise PrivateApiError("Start and end timestamps must include a UTC offset.")
    for value in (start_time, end_time):
        if value.utcoffset() != value.astimezone(zone).utcoffset():
            raise PrivateApiError("Timestamp offsets do not match the selected timezone.")
    duration = (end_time - start_time).total_seconds()
    if not 60 <= duration <= 86_400:
        raise PrivateApiError("This proof of concept accepts sessions from one minute to 24 hours.")
    if end_time > (now or datetime.now(timezone.utc)):
        raise PrivateApiError("Only completed activities can be submitted.")
    def utc(value: datetime) -> str:
        return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    return {
        "sport_id": SPORTS[sport], "start_time": utc(start_time), "end_time": utc(end_time),
        "gps_enabled": False,
    }
