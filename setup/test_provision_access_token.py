import json
import stat
import tempfile
import unittest
from importlib.util import find_spec
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request

from provision_access_token import (
    AUTHORIZATION_URL,
    CALLBACK_TIMEOUT_SECONDS,
    OAUTH_SCOPE,
    REDIRECT_PATH,
    REDIRECT_URI,
    TOKEN_URL,
    MAX_PAYLOAD_LENGTH,
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

    def test_callback_rejects_duplicates_errors_and_unexpected_fields(self) -> None:
        state = "a" * 64
        invalid_targets = (
            f"{REDIRECT_PATH}?code=one&code=two&state={state}",
            f"{REDIRECT_PATH}?error=access_denied&state={state}",
            f"{REDIRECT_PATH}?code=code&state={state}&unexpected=value",
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
        body = request.data.decode("ascii")
        self.assertIn("grant_type=authorization_code", body)
        self.assertIn("client_id=client-id", body)
        self.assertIn("client_secret=client-secret", body)
        self.assertIn("redirect_uri=http%3A%2F%2F127.0.0.1%3A8765%2Fcallback", body)
        self.assertNotIn("offline", body)

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
