import json
import io
import stat
import tempfile
import unittest
from importlib.util import find_spec
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request

from provision_access_token import (
    AUTHORIZATION_URL,
    CALLBACK_TIMEOUT_SECONDS,
    OAUTH_SCOPE,
    REDIRECT_PATH,
    REDIRECT_URI,
    TOKEN_URL,
    USER_AGENT,
    MAX_PAYLOAD_LENGTH,
    MAX_OAUTH_ERROR_CODE_LENGTH,
    MAX_OAUTH_ERROR_DESCRIPTION_LENGTH,
    OAuthError,
    authorize_and_exchange,
    build_authorization_url,
    build_provisioning_payload,
    exchange_authorization_code,
    generate_state,
    parse_callback_target,
    validate_access_token,
    wait_for_callback,
    write_provisioning_qr,
)


class ProvisionAccessTokenTest(unittest.TestCase):
    def test_validation_strips_only_outer_whitespace(self) -> None:
        token = "a" * 24
        self.assertEqual(token, validate_access_token(f"  {token}\n"))

    def test_validation_rejects_empty_short_and_internal_whitespace(self) -> None:
        with self.assertRaises(ValueError):
            validate_access_token("")
        with self.assertRaises(ValueError):
            validate_access_token("a" * 19)
        with self.assertRaises(ValueError):
            validate_access_token("a" * 10 + " " + "b" * 10)

    def test_payload_is_versioned_and_does_not_include_raw_query_unsafe_text(self) -> None:
        token = "a/b?c&d=" + "z" * 20
        payload = build_provisioning_payload(token)
        self.assertTrue(payload.startswith("whoof://provision?v=1&access_token="))
        self.assertNotIn(token, payload)

    def test_payload_rejects_one_character_over_qr_safe_ceiling(self) -> None:
        prefix = "whoof://provision?v=1&access_token="
        token = "a" * (MAX_PAYLOAD_LENGTH + 1 - len(prefix))
        self.assertEqual(MAX_PAYLOAD_LENGTH + 1, len(prefix + token))

        with self.assertRaises(ValueError):
            build_provisioning_payload(token)

    def test_authorization_url_has_exact_scope_and_no_secret(self) -> None:
        state = generate_state()
        self.assertEqual(2 * 32, len(state))
        url = build_authorization_url("client-id", state)
        parsed = urlsplit(url)
        self.assertEqual(AUTHORIZATION_URL, f"{parsed.scheme}://{parsed.netloc}{parsed.path}")
        params = parse_qs(parsed.query)
        self.assertEqual(["client-id"], params["client_id"])
        self.assertEqual([REDIRECT_URI], params["redirect_uri"])
        self.assertEqual([OAUTH_SCOPE], params["scope"])
        self.assertEqual([state], params["state"])
        self.assertNotIn("client_secret", params)
        self.assertNotIn("offline", params)

    def test_callback_requires_matching_state_and_exact_path(self) -> None:
        state = "a" * 64
        self.assertEqual(
            "one-time-code",
            parse_callback_target(
                f"{REDIRECT_PATH}?code=one-time-code&state={state}",
                state,
            ),
        )
        with self.assertRaises(OAuthError):
            parse_callback_target(f"{REDIRECT_PATH}?code=code&state={'b' * 64}", state)
        with self.assertRaises(OAuthError):
            parse_callback_target(f"/other?code=code&state={state}", state)

    def test_callback_ignores_unrecognized_fields_and_their_duplicates(self) -> None:
        state = "a" * 64
        self.assertEqual(
            "code",
            parse_callback_target(
                f"{REDIRECT_PATH}?code=code&state={state}"
                "&new_field=one&new_field=two&another_new_field=three",
                state,
            ),
        )

    def test_callback_rejects_duplicates_and_errors(self) -> None:
        state = "a" * 64
        invalid_targets = (
            f"{REDIRECT_PATH}?code=one&code=two&state={state}",
            f"{REDIRECT_PATH}?error=access_denied&state={state}",
            f"{REDIRECT_PATH}?code=code&state=",
        )
        for target in invalid_targets:
            with self.subTest(target=target), self.assertRaises(OAuthError):
                parse_callback_target(target, state)

    def test_loopback_callback_binds_the_fixed_address(self) -> None:
        server_args: list[tuple[tuple[str, int], object]] = []

        class FakeServer:
            timeout: float

            def __init__(self, address, handler) -> None:
                server_args.append((address, handler))

            def handle_request(self) -> None:
                raise OAuthError("test stop")

            def server_close(self) -> None:
                return

        with patch("provision_access_token._CallbackServer", FakeServer):
            with self.assertRaisesRegex(OAuthError, "test stop"):
                wait_for_callback("a" * 64, timeout_seconds=2)

        self.assertEqual(("127.0.0.1", 8765), server_args[0][0])
        self.assertTrue(callable(server_args[0][1]))

    def test_authorize_opens_browser_only_after_callback_is_bound(self) -> None:
        events: list[str] = []

        def fake_callback_waiter(state: str, *, timeout_seconds: int, on_ready) -> str:
            self.assertEqual(CALLBACK_TIMEOUT_SECONDS, timeout_seconds)
            events.append("callback-ready")
            on_ready()
            return "authorization-code"

        def fake_browser(url: str) -> bool:
            events.append("browser")
            self.assertNotIn("client-secret", url)
            return True

        def fake_exchange(code: str, client_id: str, client_secret: str) -> str:
            self.assertEqual("authorization-code", code)
            self.assertEqual("client-id", client_id)
            self.assertEqual("client-secret", client_secret)
            return "a" * 24

        token = authorize_and_exchange(
            "client-id",
            "client-secret",
            browser_open=fake_browser,
            callback_waiter=fake_callback_waiter,
            token_exchanger=fake_exchange,
        )
        self.assertEqual(["callback-ready", "browser"], events)
        self.assertEqual("a" * 24, token)

    def test_token_exchange_posts_form_without_offline_scope(self) -> None:
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = json.dumps({"access_token": "a" * 24}).encode()

        with patch("provision_access_token.urlopen", return_value=response) as urlopen:
            token = exchange_authorization_code("authorization-code", "client-id", "client-secret")

        self.assertEqual("a" * 24, token)
        request = urlopen.call_args.args[0]
        self.assertIsInstance(request, Request)
        self.assertEqual(TOKEN_URL, request.full_url)
        self.assertEqual(USER_AGENT, request.get_header("User-agent"))
        body = request.data.decode("ascii")
        self.assertIn("grant_type=authorization_code", body)
        self.assertIn("client_id=client-id", body)
        self.assertIn("client_secret=client-secret", body)
        self.assertIn("redirect_uri=http%3A%2F%2F127.0.0.1%3A8765%2Fcallback", body)
        self.assertNotIn("offline", body)

    def test_token_exchange_reports_safe_oauth_json_error(self) -> None:
        error = HTTPError(
            TOKEN_URL,
            403,
            "Forbidden",
            {},
            io.BytesIO(
                json.dumps(
                    {
                        "error": "invalid_client",
                        "error_description": "The client authentication failed.",
                        "error_uri": "https://example.test/diagnostics",
                    }
                ).encode()
            ),
        )
        with patch("provision_access_token.urlopen", side_effect=error):
            with self.assertRaisesRegex(
                OAuthError,
                r"WHOOP token exchange failed \(HTTP 403\): invalid_client - "
                r"The client authentication failed\.\Z",
            ):
                exchange_authorization_code(
                    "authorization-code",
                    "client-id",
                    "client-secret",
                )

    def test_token_exchange_preserves_useful_credential_terms(self) -> None:
        error = HTTPError(
            TOKEN_URL,
            403,
            "Forbidden",
            {},
            io.BytesIO(
                json.dumps(
                    {
                        "error": "invalid_grant",
                        "error_description": "Authorization code expired.",
                    }
                ).encode()
            ),
        )
        with patch("provision_access_token.urlopen", side_effect=error):
            with self.assertRaisesRegex(
                OAuthError,
                r"invalid_grant - Authorization code expired\.\Z",
            ):
                exchange_authorization_code(
                    "authorization-code",
                    "client-id",
                    "client-secret",
                )

    def test_token_exchange_falls_back_for_malformed_or_non_object_body(self) -> None:
        for body in (b"not json", json.dumps(["invalid_client"]).encode()):
            error = HTTPError(TOKEN_URL, 403, "Forbidden", {}, io.BytesIO(body))
            with self.subTest(body=body), patch(
                "provision_access_token.urlopen", side_effect=error
            ):
                with self.assertRaisesRegex(
                    OAuthError,
                    r"WHOOP token exchange failed \(HTTP 403\)\.\Z",
                ):
                    exchange_authorization_code(
                        "authorization-code",
                        "client-id",
                        "client-secret",
                    )

    def test_token_exchange_ignores_sensitive_and_unexpected_fields(self) -> None:
        body = json.dumps(
            {
                "error": "invalid_client",
                "error_description": (
                    "bad client-secret=secret authorization-code=code "
                    "access_token=access refresh_token=refresh\nnext"
                ),
                "client_secret": "secret-field",
                "access_token": "token-field",
                "request": "request-body-field",
            }
        ).encode()
        error = HTTPError(TOKEN_URL, 403, "Forbidden", {}, io.BytesIO(body))
        with patch("provision_access_token.urlopen", side_effect=error):
            with self.assertRaises(OAuthError) as raised:
                exchange_authorization_code(
                    "authorization-code",
                    "client-id",
                    "client-secret",
                )
        message = str(raised.exception)
        self.assertIn("invalid_client", message)
        self.assertIn("bad [redacted] [redacted] [redacted] [redacted] next", message)
        self.assertNotIn("secret-field", message)
        self.assertNotIn("token-field", message)
        self.assertNotIn("request-body-field", message)
        self.assertNotIn("authorization-code", message)
        self.assertNotIn("client-secret", message)
        self.assertNotIn("access", message)
        self.assertNotIn("refresh", message)

    def test_token_exchange_sanitizes_and_bounds_error_fields(self) -> None:
        long_code = "x" * (MAX_OAUTH_ERROR_CODE_LENGTH + 1)
        long_description = "safe\x00\x01\n" + ("d" * 400)
        body = json.dumps(
            {"error": long_code, "error_description": long_description}
        ).encode()
        error = HTTPError(TOKEN_URL, 403, "Forbidden", {}, io.BytesIO(body))
        with patch("provision_access_token.urlopen", side_effect=error):
            with self.assertRaises(OAuthError) as raised:
                exchange_authorization_code(
                    "authorization-code",
                    "client-id",
                    "client-secret",
                )
        message = str(raised.exception)
        self.assertTrue(message.endswith("d"))
        self.assertLessEqual(
            len(message),
            len("WHOOP token exchange failed (HTTP 403): ")
            + MAX_OAUTH_ERROR_DESCRIPTION_LENGTH,
        )
        self.assertNotIn("\x00", message)
        self.assertNotIn("\x01", message)

        for invalid_code in ("invalid code", "invalid/\ncode", "\U0001f600"):
            body = json.dumps({"error": invalid_code}).encode()
            error = HTTPError(TOKEN_URL, 403, "Forbidden", {}, io.BytesIO(body))
            with self.subTest(invalid_code=invalid_code), patch(
                "provision_access_token.urlopen", side_effect=error
            ):
                with self.assertRaisesRegex(
                    OAuthError,
                    r"WHOOP token exchange failed \(HTTP 403\)\.\Z",
                ):
                    exchange_authorization_code(
                        "authorization-code",
                        "client-id",
                        "client-secret",
                    )

    def test_token_exchange_falls_back_for_oversized_error_body(self) -> None:
        error = HTTPError(
            TOKEN_URL,
            403,
            "Forbidden",
            {},
            io.BytesIO(b"{" + b" " * 8192 + b"}"),
        )
        with patch("provision_access_token.urlopen", side_effect=error):
            with self.assertRaisesRegex(
                OAuthError,
                r"WHOOP token exchange failed \(HTTP 403\)\.\Z",
            ):
                exchange_authorization_code(
                    "authorization-code",
                    "client-id",
                    "client-secret",
                )

    @unittest.skipUnless(find_spec("qrcode"), "qrcode dependency is not installed")
    def test_qr_output_is_private_and_does_not_overwrite_by_default(self) -> None:
        token = "a" * 24
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "provision.png"
            write_provisioning_qr(token, output)
            self.assertTrue(output.is_file())
            self.assertEqual(0o600, stat.S_IMODE(output.stat().st_mode))
            with self.assertRaises(FileExistsError):
                write_provisioning_qr(token, output)


if __name__ == "__main__":
    unittest.main()
