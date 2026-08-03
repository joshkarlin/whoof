import json
import io
import stat
import sys
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
    OAuthCredentials,
    OAuthError,
    OAuthTokens,
    authorize_and_exchange,
    build_authorization_url,
    build_provisioning_payload,
    build_provisioning_payload_v2,
    exchange_authorization_code,
    generate_state,
    main,
    parse_callback_target,
    parse_oauth_token_response,
    validate_access_token,
    validate_client_credential,
    validate_refresh_token,
    wait_for_callback,
    write_provisioning_qr,
)

SYNTHETIC_CLIENT_ID = "synthetic-client-id"
SYNTHETIC_CLIENT_SECRET = "synthetic-client-secret"
SYNTHETIC_ACCESS_TOKEN = "synthetic-access-token-123456"
SYNTHETIC_REFRESH_TOKEN = "synthetic-refresh-token-123456"


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

    def test_v2_payload_has_exactly_all_credentials_and_expiry(self) -> None:
        payload = build_provisioning_payload_v2(
            SYNTHETIC_CLIENT_ID,
            SYNTHETIC_CLIENT_SECRET,
            SYNTHETIC_ACCESS_TOKEN,
            SYNTHETIC_REFRESH_TOKEN,
            3600,
        )
        parsed = parse_qs(urlsplit(payload).query)

        self.assertEqual(
            {
                "v",
                "client_id",
                "client_secret",
                "access_token",
                "refresh_token",
                "expires_in",
            },
            set(parsed),
        )
        self.assertEqual(["2"], parsed["v"])
        self.assertEqual([SYNTHETIC_CLIENT_ID], parsed["client_id"])
        self.assertEqual([SYNTHETIC_CLIENT_SECRET], parsed["client_secret"])
        self.assertEqual([SYNTHETIC_ACCESS_TOKEN], parsed["access_token"])
        self.assertEqual([SYNTHETIC_REFRESH_TOKEN], parsed["refresh_token"])
        self.assertEqual(["3600"], parsed["expires_in"])

    def test_v2_payload_validates_all_credentials_and_expiry(self) -> None:
        with self.assertRaises(ValueError):
            build_provisioning_payload_v2(
                SYNTHETIC_CLIENT_ID,
                SYNTHETIC_CLIENT_SECRET,
                "a" * 24,
                "r" * 19,
                3600,
            )
        with self.assertRaises(ValueError):
            build_provisioning_payload_v2(
                SYNTHETIC_CLIENT_ID,
                SYNTHETIC_CLIENT_SECRET,
                "a" * 24,
                "r" * 24,
                0,
            )
        with self.assertRaises(ValueError):
            build_provisioning_payload_v2(
                "client id",
                SYNTHETIC_CLIENT_SECRET,
                "a" * 24,
                "r" * 24,
                3600,
            )
        with self.assertRaises(ValueError):
            build_provisioning_payload_v2(
                SYNTHETIC_CLIENT_ID,
                SYNTHETIC_CLIENT_SECRET,
                "a" * 24,
                "r" * 10 + "\n" + "s" * 10,
                3600,
            )

    def test_authorization_url_has_exact_scope_and_no_secret(self) -> None:
        state = generate_state()
        self.assertEqual(2 * 32, len(state))
        url = build_authorization_url(SYNTHETIC_CLIENT_ID, state)
        parsed = urlsplit(url)
        self.assertEqual(AUTHORIZATION_URL, f"{parsed.scheme}://{parsed.netloc}{parsed.path}")
        params = parse_qs(parsed.query)
        self.assertEqual([SYNTHETIC_CLIENT_ID], params["client_id"])
        self.assertEqual([REDIRECT_URI], params["redirect_uri"])
        expected_scope = "read:recovery read:cycles read:sleep read:workout offline"
        self.assertEqual(expected_scope, OAUTH_SCOPE)
        self.assertEqual([expected_scope], params["scope"])
        self.assertEqual([state], params["state"])
        self.assertNotIn("client_secret", params)
        self.assertNotIn(SYNTHETIC_CLIENT_SECRET, url)

    def test_client_credential_validation_rejects_non_single_line_values(self) -> None:
        for invalid_value in ("", " \t", "client id", "client\nid", "client\x00id", "cliënt", None, 123):
            with self.subTest(invalid_value=invalid_value), self.assertRaisesRegex(
                ValueError,
                r"Client (?:ID|secret) is missing or malformed\.\Z",
            ):
                validate_client_credential(invalid_value, "Client ID")

        self.assertEqual(
            SYNTHETIC_CLIENT_ID,
            validate_client_credential(f"  {SYNTHETIC_CLIENT_ID}\n", "Client ID"),
        )

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
            self.assertNotIn(SYNTHETIC_CLIENT_SECRET, url)
            return True

        def fake_exchange(
            code: str,
            client_id: str,
            client_secret: str,
        ) -> OAuthCredentials:
            self.assertEqual("authorization-code", code)
            self.assertEqual(SYNTHETIC_CLIENT_ID, client_id)
            self.assertEqual(SYNTHETIC_CLIENT_SECRET, client_secret)
            return OAuthCredentials(
                SYNTHETIC_CLIENT_ID,
                SYNTHETIC_CLIENT_SECRET,
                SYNTHETIC_ACCESS_TOKEN,
                SYNTHETIC_REFRESH_TOKEN,
                3600,
            )

        tokens = authorize_and_exchange(
            SYNTHETIC_CLIENT_ID,
            SYNTHETIC_CLIENT_SECRET,
            browser_open=fake_browser,
            callback_waiter=fake_callback_waiter,
            token_exchanger=fake_exchange,
        )
        self.assertEqual(["callback-ready", "browser"], events)
        self.assertEqual(
            OAuthCredentials(
                SYNTHETIC_CLIENT_ID,
                SYNTHETIC_CLIENT_SECRET,
                SYNTHETIC_ACCESS_TOKEN,
                SYNTHETIC_REFRESH_TOKEN,
                3600,
            ),
            tokens,
        )

    def test_token_exchange_posts_form_without_authorization_scope(self) -> None:
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = json.dumps(
            {
                "access_token": SYNTHETIC_ACCESS_TOKEN,
                "refresh_token": SYNTHETIC_REFRESH_TOKEN,
                "expires_in": 3600,
            }
        ).encode()

        with patch("provision_access_token.urlopen", return_value=response) as urlopen:
            credentials = exchange_authorization_code(
                "authorization-code",
                SYNTHETIC_CLIENT_ID,
                SYNTHETIC_CLIENT_SECRET,
            )

        self.assertEqual(
            OAuthCredentials(
                SYNTHETIC_CLIENT_ID,
                SYNTHETIC_CLIENT_SECRET,
                SYNTHETIC_ACCESS_TOKEN,
                SYNTHETIC_REFRESH_TOKEN,
                3600,
            ),
            credentials,
        )
        request = urlopen.call_args.args[0]
        self.assertIsInstance(request, Request)
        self.assertEqual(TOKEN_URL, request.full_url)
        self.assertNotIn(SYNTHETIC_CLIENT_SECRET, request.full_url)
        self.assertEqual(USER_AGENT, request.get_header("User-agent"))
        body = request.data.decode("ascii")
        self.assertIn("grant_type=authorization_code", body)
        self.assertIn(f"client_id={SYNTHETIC_CLIENT_ID}", body)
        self.assertIn(f"client_secret={SYNTHETIC_CLIENT_SECRET}", body)
        self.assertIn("redirect_uri=http%3A%2F%2F127.0.0.1%3A8765%2Fcallback", body)
        self.assertNotIn("scope=", body)

    def test_cli_writes_v2_and_does_not_print_credentials(self) -> None:
        credentials = OAuthCredentials(
            SYNTHETIC_CLIENT_ID,
            SYNTHETIC_CLIENT_SECRET,
            SYNTHETIC_ACCESS_TOKEN,
            SYNTHETIC_REFRESH_TOKEN,
            3600,
        )
        output = Path("synthetic-provision.png")
        with (
            patch.object(sys, "argv", ["provision_access_token.py", "--output", str(output)]),
            patch("builtins.input", return_value=SYNTHETIC_CLIENT_ID),
            patch("provision_access_token.getpass.getpass", return_value=SYNTHETIC_CLIENT_SECRET),
            patch("provision_access_token.authorize_and_exchange", return_value=credentials),
            patch("provision_access_token.write_provisioning_qr") as write_qr,
            patch("builtins.print") as print_output,
        ):
            self.assertEqual(0, main())

        write_qr.assert_called_once_with(
            SYNTHETIC_ACCESS_TOKEN,
            output,
            client_id=SYNTHETIC_CLIENT_ID,
            client_secret=SYNTHETIC_CLIENT_SECRET,
            refresh_token=SYNTHETIC_REFRESH_TOKEN,
            expires_in=3600,
            overwrite=False,
        )
        printed = " ".join(
            str(argument)
            for call in print_output.call_args_list
            for argument in call.args
        )
        self.assertNotIn(SYNTHETIC_CLIENT_SECRET, printed)
        self.assertNotIn(SYNTHETIC_ACCESS_TOKEN, printed)
        self.assertNotIn(SYNTHETIC_REFRESH_TOKEN, printed)

    def test_token_response_parser_returns_both_validated_tokens(self) -> None:
        tokens = parse_oauth_token_response(
            json.dumps(
                {
                    "access_token": f"  {'a' * 24}\n",
                    "refresh_token": f"  {'r' * 24}\n",
                    "token_type": "Bearer",
                    "expires_in": 3600,
                }
            ).encode()
        )

        self.assertEqual(OAuthTokens("a" * 24, "r" * 24, 3600), tokens)

    def test_token_response_parser_requires_strict_access_and_refresh_tokens(self) -> None:
        invalid_responses = (
            {"refresh_token": "r" * 24},
            {"access_token": "a" * 24},
            {"access_token": "a" * 24, "refresh_token": ""},
            {"access_token": "a" * 24, "refresh_token": "r" * 10 + " " + "s" * 10},
            {"access_token": "a" * 19, "refresh_token": "r" * 24},
            {"access_token": 123, "refresh_token": "r" * 24},
            {"access_token": "a" * 24, "refresh_token": None},
            {"access_token": "a" * 24, "refresh_token": "r" * 24},
            {"access_token": "a" * 24, "refresh_token": "r" * 24, "expires_in": 0},
            {"access_token": "a" * 24, "refresh_token": "r" * 24, "expires_in": -1},
            {"access_token": "a" * 24, "refresh_token": "r" * 24, "expires_in": 1.5},
            {"access_token": "a" * 24, "refresh_token": "r" * 24, "expires_in": "3600"},
            {"access_token": "a" * 24, "refresh_token": "r" * 24, "expires_in": True},
        )
        for response in invalid_responses:
            with self.subTest(response=response), self.assertRaises(OAuthError):
                parse_oauth_token_response(json.dumps(response).encode())

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
                    SYNTHETIC_CLIENT_ID,
                    SYNTHETIC_CLIENT_SECRET,
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
                    SYNTHETIC_CLIENT_ID,
                    SYNTHETIC_CLIENT_SECRET,
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
                        SYNTHETIC_CLIENT_ID,
                        SYNTHETIC_CLIENT_SECRET,
                    )

    def test_token_exchange_ignores_sensitive_and_unexpected_fields(self) -> None:
        body = json.dumps(
            {
                "error": "invalid_client",
                "error_description": (
                    f"bad client-secret={SYNTHETIC_CLIENT_SECRET} "
                    f"authorization-code=synthetic-authorization-code "
                    "access_token=synthetic-access-token "
                    "refresh_token=synthetic-refresh-token\nnext"
                ),
                "client_secret": "synthetic-secret-field",
                "access_token": "synthetic-token-field",
                "request": "synthetic-request-body-field",
            }
        ).encode()
        error = HTTPError(TOKEN_URL, 403, "Forbidden", {}, io.BytesIO(body))
        with patch("provision_access_token.urlopen", side_effect=error):
            with self.assertRaises(OAuthError) as raised:
                exchange_authorization_code(
                    "authorization-code",
                    SYNTHETIC_CLIENT_ID,
                    SYNTHETIC_CLIENT_SECRET,
                )
        message = str(raised.exception)
        self.assertIn("invalid_client", message)
        self.assertIn("bad [redacted] [redacted] [redacted] [redacted] next", message)
        self.assertNotIn("synthetic-secret-field", message)
        self.assertNotIn("synthetic-token-field", message)
        self.assertNotIn("synthetic-request-body-field", message)
        self.assertNotIn("synthetic-authorization-code", message)
        self.assertNotIn(SYNTHETIC_CLIENT_SECRET, message)
        self.assertNotIn("synthetic-access-token", message)
        self.assertNotIn("synthetic-refresh-token", message)

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
                    SYNTHETIC_CLIENT_ID,
                    SYNTHETIC_CLIENT_SECRET,
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
                        SYNTHETIC_CLIENT_ID,
                        SYNTHETIC_CLIENT_SECRET,
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
                    SYNTHETIC_CLIENT_ID,
                    SYNTHETIC_CLIENT_SECRET,
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
