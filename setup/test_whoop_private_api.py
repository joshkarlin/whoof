import base64
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import stat
import tempfile
import unittest
from urllib.error import HTTPError
from unittest.mock import Mock, patch

import whoop_private_api as api


ACCESS = "synthetic-access-token-1234567890"
REFRESH = "synthetic-refresh-token-1234567890"
PASSWORD = "synthetic-password-never-persist-this"
EMAIL = "synthetic@example.invalid"
NOW = datetime(2026, 10, 27, 12, 0, tzinfo=timezone.utc)


def tokens() -> api.SessionTokens:
    return api.SessionTokens(ACCESS, REFRESH, NOW.timestamp() + 3600)


def browser_payload(*, exp: object = NOW.timestamp() + 3600, refresh_token: str = REFRESH) -> str:
    encoded = base64.urlsafe_b64encode(json.dumps({"exp": exp}).encode()).decode().rstrip("=")
    return json.dumps({"access_token": f"synthetic.{encoded}.unsigned", "refresh_token": refresh_token})


class PrivateWhoopApiTest(unittest.TestCase):
    def test_parse_browser_session_accepts_unsigned_shape_and_uses_exp_only_as_metadata(self) -> None:
        payload = browser_payload()
        result = api.parse_browser_session(payload, clock=lambda: NOW.timestamp())
        self.assertEqual(json.loads(payload)["access_token"], result.access_token)
        self.assertEqual(REFRESH, result.refresh_token)
        self.assertEqual(NOW.timestamp() + 3600, result.expires_at)

    def test_parse_browser_session_rejects_malformed_expired_and_missing_refresh_safely(self) -> None:
        cases = (
            "{not-json",
            json.dumps({"access_token": "synthetic-token", "refresh_token": REFRESH}),
            browser_payload(exp="tomorrow"),
            browser_payload(exp=float("inf")),
            browser_payload(exp=NOW.timestamp()),
            browser_payload(refresh_token=""),
            json.dumps({"access_token": ACCESS}),
        )
        for payload in cases:
            with self.subTest(payload=payload), self.assertRaises(api.PrivateApiError) as raised:
                api.parse_browser_session(payload, clock=lambda: NOW.timestamp())
            self.assertNotIn(ACCESS, str(raised.exception))
            self.assertNotIn(REFRESH, str(raised.exception))

    def test_private_api_error_exposes_optional_code(self) -> None:
        plain = api.PrivateApiError("safe")
        coded = api.PrivateApiError("safe", code="NotAuthorizedException")
        self.assertIsNone(plain.code)
        self.assertEqual("NotAuthorizedException", coded.code)

    def test_request_json_extracts_allowlisted_auth_code_without_echoing_fields(self) -> None:
        secret = "synthetic-private-error-detail-123"
        error = HTTPError(api.AUTH_URL, 400, "bad request", {}, None)
        error.read = Mock(return_value=json.dumps({
            "__type": "com.amazonaws.cognito.identity.provider#NotAuthorizedException",
            "message": secret,
            "access_token": ACCESS,
        }).encode())
        with patch.object(api, "build_opener", return_value=Mock(open=Mock(side_effect=error))):
            with self.assertRaises(api.PrivateApiError) as raised:
                api.request_json("POST", api.AUTH_URL, {}, {})
        self.assertEqual("NotAuthorizedException", raised.exception.code)
        self.assertNotIn(secret, str(raised.exception))
        self.assertNotIn(ACCESS, str(raised.exception))

    def test_sign_in_and_refresh_auth_errors_are_distinguished(self) -> None:
        rejected = api.PrivateApiError("rejected", status=401, code="NotAuthorizedException")
        with self.assertRaises(api.PrivateApiError) as sign_in:
            api.PrivateWhoopClient(Mock(side_effect=rejected)).begin_login(EMAIL, PASSWORD)
        with self.assertRaises(api.PrivateApiError) as refresh:
            api.PrivateWhoopClient(Mock(side_effect=rejected)).refresh(tokens())
        self.assertNotEqual(str(sign_in.exception), str(refresh.exception))
        self.assertRegex(str(sign_in.exception).lower(), r"sign.?in|login")
        self.assertRegex(str(refresh.exception).lower(), r"refresh|session")

    def test_begin_login_accepts_immediate_authentication(self) -> None:
        transport = Mock(return_value={"AuthenticationResult": {
            "AccessToken": ACCESS, "RefreshToken": REFRESH, "ExpiresIn": 3600,
        }})
        result = api.PrivateWhoopClient(transport, clock=lambda: NOW.timestamp()).begin_login(
            f" {EMAIL} ", PASSWORD,
        )
        self.assertEqual(tokens(), result)
        method, url, headers, body = transport.call_args.args
        self.assertEqual(("POST", api.AUTH_URL), (method, url))
        self.assertEqual("InitiateAuth", headers["X-Amz-Target"].rsplit(".", 1)[-1])
        self.assertEqual(EMAIL, body["AuthParameters"]["USERNAME"])
        self.assertEqual(PASSWORD, body["AuthParameters"]["PASSWORD"])

    def test_begin_login_returns_each_supported_mfa_challenge(self) -> None:
        for challenge_name in api.MFA_FIELDS:
            with self.subTest(challenge=challenge_name):
                transport = Mock(return_value={
                    "ChallengeName": challenge_name,
                    "Session": "synthetic-opaque-session-value-123456",
                    "ChallengeParameters": {"USERNAME": "canonical-user-123"},
                })
                result = api.PrivateWhoopClient(transport).begin_login(EMAIL, PASSWORD)
                self.assertIsInstance(result, api.LoginChallenge)
                self.assertEqual(challenge_name, result.name)
                self.assertEqual("canonical-user-123", result.username)

    def test_complete_login_uses_canonical_username_and_challenge_field(self) -> None:
        for challenge_name, code_field in api.MFA_FIELDS.items():
            with self.subTest(challenge=challenge_name):
                challenge = api.LoginChallenge(
                    challenge_name, "synthetic-session-value-123456789", "canonical-user-123",
                )
                transport = Mock(return_value={"AuthenticationResult": {
                    "AccessToken": ACCESS, "RefreshToken": REFRESH, "ExpiresIn": 3600,
                }})
                result = api.PrivateWhoopClient(
                    transport, clock=lambda: NOW.timestamp(),
                ).complete_login(challenge, "  123456  ")
                self.assertEqual(tokens(), result)
                body = transport.call_args.args[3]
                self.assertEqual(challenge_name, body["ChallengeName"])
                self.assertEqual(challenge.session, body["Session"])
                self.assertEqual("canonical-user-123", body["ChallengeResponses"]["USERNAME"])
                self.assertEqual("123456", body["ChallengeResponses"][code_field])

    def test_unsupported_challenge_rejects_without_secret_leakage(self) -> None:
        transport = Mock(return_value={
            "ChallengeName": "NEW_SECRET_CHALLENGE", "Session": "synthetic-secret-session-123456",
            "ChallengeParameters": {"USERNAME": EMAIL, "PASSWORD": PASSWORD},
        })
        with self.assertRaises(api.PrivateApiError) as raised:
            api.PrivateWhoopClient(transport).begin_login(EMAIL, PASSWORD)
        rendered = str(raised.exception)
        self.assertNotIn(PASSWORD, rendered)
        self.assertNotIn(EMAIL, rendered)
        self.assertNotIn("synthetic-secret-session", rendered)

    def test_refresh_retains_original_refresh_token_when_response_omits_it(self) -> None:
        transport = Mock(return_value={"AuthenticationResult": {
            "AccessToken": "synthetic-new-access-token-123456789", "ExpiresIn": 1800,
        }})
        refreshed = api.PrivateWhoopClient(transport, clock=lambda: NOW.timestamp()).refresh(tokens())
        self.assertEqual(REFRESH, refreshed.refresh_token)
        self.assertEqual("synthetic-new-access-token-123456789", refreshed.access_token)
        self.assertEqual(REFRESH, transport.call_args.args[3]["AuthParameters"]["REFRESH_TOKEN"])

    def test_check_account_is_a_read_only_get(self) -> None:
        transport = Mock(return_value={"user": {"id": "12345"}})
        api.PrivateWhoopClient(transport).check_account(tokens())
        method, url, _headers, body = transport.call_args.args
        self.assertEqual(("GET", api.ACCOUNT_URL, None), (method, url, body))
        transport.assert_called_once()

    def test_http_errors_do_not_echo_response_secrets(self) -> None:
        secret_body = (ACCESS + PASSWORD + EMAIL).encode()
        error = HTTPError(api.AUTH_URL, 400, "bad request", {}, None)
        error.read = Mock(return_value=secret_body)
        with patch.object(api, "build_opener", return_value=Mock(open=Mock(side_effect=error))):
            with self.assertRaises(api.PrivateApiError) as raised:
                api.request_json("POST", api.AUTH_URL, {}, {"password": PASSWORD})
        self.assertEqual(400, raised.exception.status)
        self.assertNotIn(ACCESS, str(raised.exception))
        self.assertNotIn(PASSWORD, str(raised.exception))
        self.assertNotIn(EMAIL, str(raised.exception))

    def test_request_json_disables_redirects(self) -> None:
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = b'{"ok": true}'
        with patch.object(api, "build_opener", return_value=Mock(open=Mock(return_value=response))) as opener:
            self.assertEqual({"ok": True}, api.request_json("GET", api.ACCOUNT_URL, {}, None))
        handler = opener.call_args.args[0]
        self.assertIsInstance(handler, api._NoRedirect)
        request = opener.return_value.open.call_args.args[0]
        self.assertIsNone(handler.redirect_request(request, None, 302, "Found", {}, "https://evil.invalid"))

    def test_session_tokens_redact_repr(self) -> None:
        rendered = repr(tokens())
        self.assertNotIn(ACCESS, rendered)
        self.assertNotIn(REFRESH, rendered)

    def test_session_persists_atomically_as_owner_only_json_without_password_or_email(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "private" / "session.json"
            real_replace = os.replace
            replace_calls = []

            def record_replace(source, destination):
                replace_calls.append((Path(source), Path(destination)))
                return real_replace(source, destination)

            with patch.object(api.os, "replace", side_effect=record_replace):
                api.save_session(path, tokens())
            self.assertEqual(1, len(replace_calls))
            temporary, destination = replace_calls[0]
            self.assertEqual(path, destination)
            self.assertEqual(path.parent, temporary.parent)
            self.assertFalse(temporary.exists())
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            self.assertEqual(0o700, stat.S_IMODE(path.parent.stat().st_mode))
            raw = path.read_text()
            self.assertNotIn(PASSWORD, raw)
            self.assertNotIn(EMAIL, raw)
            self.assertEqual(tokens(), api.load_session(path))

    def test_session_loader_rejects_malformed_and_expired_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "session.json"
            for contents in (
                {"version": 1, "access_token": ACCESS, "refresh_token": REFRESH, "expires_at": "later"},
                {"version": 1, "access_token": ACCESS, "refresh_token": REFRESH, "expires_at": float("inf")},
                {"version": 1, "access_token": ACCESS, "refresh_token": REFRESH, "expires_at": 0},
                {"version": 2, "access_token": ACCESS, "refresh_token": REFRESH, "expires_at": NOW.timestamp()},
            ):
                with self.subTest(contents=contents):
                    path.write_text(json.dumps(contents))
                    path.chmod(0o600)
                    with self.assertRaises(api.PrivateApiError):
                        api.load_session(path)

    def test_build_activity_converts_dst_offsets_to_utc(self) -> None:
        payload = api.build_activity(
            "running", "2026-10-25T01:10:00+00:00", "2026-10-25T02:10:00+00:00",
            "Europe/London", now=NOW,
        )
        self.assertEqual(api.SPORTS["running"], payload["sport_id"])
        self.assertEqual("2026-10-25T01:10:00.000Z", payload["start_time"])
        self.assertEqual("2026-10-25T02:10:00.000Z", payload["end_time"])
        self.assertFalse(payload["gps_enabled"])

    def test_build_activity_rejects_future_end_before_start_and_unknown_sport(self) -> None:
        cases = (
            ("running", "2026-10-27T11:00:00Z", "2026-10-27T13:00:00Z", "UTC"),
            ("running", "2026-10-27T11:00:00Z", "2026-10-27T10:00:00Z", "UTC"),
            ("unknown", "2026-10-27T10:00:00Z", "2026-10-27T11:00:00Z", "UTC"),
        )
        for args in cases:
            with self.subTest(args=args), self.assertRaises(api.PrivateApiError):
                api.build_activity(*args, now=NOW)

    def test_create_activity_does_not_retry_401(self) -> None:
        transport = Mock(side_effect=api.PrivateApiError("rejected", status=401))
        client = api.PrivateWhoopClient(transport)
        with self.assertRaises(api.PrivateApiError):
            client.create_activity(tokens(), {"sport_id": 0}, "UTC")
        transport.assert_called_once()

    def test_create_activity_marks_network_server_and_invalid_receipt_uncertain(self) -> None:
        cases = (
            api.PrivateApiError("offline"),
            api.PrivateApiError("server failure", status=503),
            {"id": "not-a-uuid"},
        )
        for outcome in cases:
            with self.subTest(outcome=outcome):
                transport = Mock(side_effect=outcome if isinstance(outcome, Exception) else None,
                                 return_value=outcome if isinstance(outcome, dict) else None)
                client = api.PrivateWhoopClient(transport)
                with self.assertRaises(api.SubmissionUncertainError):
                    client.create_activity(tokens(), {"sport_id": 0}, "UTC")
                transport.assert_called_once()


if __name__ == "__main__":
    unittest.main()
