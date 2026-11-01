from __future__ import annotations

import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from urllib.parse import parse_qs, urlsplit
from unittest.mock import Mock, patch

import provision_private_session as provision
from whoop_private_api import PrivateApiError, SessionTokens


ACCESS = "A" * 20
REFRESH = "R" * 20
NOW = 1_800_000_000


class ProvisionPrivateSessionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.tokens = SessionTokens(ACCESS, REFRESH, NOW + 120)

    def test_uri_has_android_contract_and_integer_expiry(self) -> None:
        uri = provision.provisioning_uri(self.tokens, now=NOW)
        parsed = urlsplit(uri)
        self.assertEqual(("whoof", "private-provision", ""), (parsed.scheme, parsed.netloc, parsed.path))
        self.assertEqual({
            "v": ["1"], "access_token": [ACCESS], "refresh_token": [REFRESH],
            "expires_at": [str(NOW + 120)],
        }, parse_qs(parsed.query, strict_parsing=True))
        self.assertEqual(
            "whoof://private-provision?v=1&access_token=" + ACCESS + "&refresh_token=" + REFRESH + "&expires_at=" + str(NOW + 120),
            uri,
        )

    def test_expiry_must_be_more_than_sixty_seconds_after_now(self) -> None:
        with self.assertRaises(provision.ProvisionError):
            provision.provisioning_uri(SessionTokens(ACCESS, REFRESH, NOW + 60), now=NOW)
        self.assertEqual(
            NOW + 61,
            int(parse_qs(urlsplit(provision.provisioning_uri(
                SessionTokens(ACCESS, REFRESH, NOW + 61), now=NOW,
            )).query)["expires_at"][0]),
        )

    def test_token_character_and_length_boundaries(self) -> None:
        provision.provisioning_uri(SessionTokens("x" * 2048, REFRESH, NOW + 61), now=NOW)
        for token in ("x" * 19, "x" * 2049, "x" * 19 + "\n", "x" * 19 + " "):
            with self.subTest(length=len(token)):
                with self.assertRaises(provision.ProvisionError):
                    provision.provisioning_uri(SessionTokens(token, REFRESH, NOW + 61), now=NOW)

    def test_payload_must_fit_android_byte_limit(self) -> None:
        exact_limit = SessionTokens("a" * 1410, "b" * 1410, NOW + 61)
        uri = provision.provisioning_uri(exact_limit, now=NOW)
        self.assertEqual(2900, len(uri.encode("utf-8")))
        with self.assertRaises(provision.ProvisionError):
            provision.provisioning_uri(SessionTokens("a" * 1411, "b" * 1410, NOW + 61), now=NOW)

    def test_png_is_owner_only_and_refuses_existing_output(self) -> None:
        output = self.root / "private.png"
        uri = provision.provisioning_uri(self.tokens, now=NOW)
        provision.write_private_qr(uri, output)
        self.assertEqual(0o600, os.stat(output).st_mode & 0o777)
        original = output.read_bytes()
        with self.assertRaises(provision.ProvisionError):
            provision.write_private_qr(uri, output)
        self.assertEqual(original, output.read_bytes())
        self.assertEqual([output], list(self.root.iterdir()))

    def test_overwrite_replaces_existing_png_atomically(self) -> None:
        output = self.root / "private.png"
        output.write_bytes(b"old")
        provision.write_private_qr(provision.provisioning_uri(self.tokens, now=NOW), output, overwrite=True)
        self.assertNotEqual(b"old", output.read_bytes())
        self.assertEqual(0o600, os.stat(output).st_mode & 0o777)
        self.assertEqual([output], list(self.root.iterdir()))

    def test_failed_png_generation_cleans_temporary_file(self) -> None:
        output = self.root / "private.png"
        with patch.object(provision.qrcode, "QRCode", side_effect=RuntimeError("synthetic-secret")):
            with self.assertRaises(provision.ProvisionError):
                provision.write_private_qr("synthetic payload", output)
        self.assertEqual([], list(self.root.iterdir()))

    def test_main_uses_ready_local_session_then_checks_before_export(self) -> None:
        output = self.root / "private.png"
        client = Mock()
        order: list[str] = []
        client.check_account.side_effect = lambda tokens: order.append("check")
        with patch.object(provision, "PRIVATE_DIR", self.root / "account"), patch.object(
            provision, "exclusive_session", side_effect=lambda _directory: contextlib.nullcontext(),
        ) as lock, patch.object(provision, "PrivateWhoopClient", return_value=client), patch.object(
            provision, "ready_session", side_effect=lambda _client, _directory: order.append("ready") or self.tokens,
        ) as ready, patch.object(provision, "write_private_qr", side_effect=lambda *_args, **_kwargs: order.append("write")):
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                result = provision.main(["--output", str(output)])
        self.assertEqual(0, result)
        self.assertEqual(["ready", "check", "write"], order)
        lock.assert_called_once()
        ready.assert_called_once_with(client, self.root / "account")
        client.check_account.assert_called_once_with(self.tokens)
        self.assertIn(str(output), stdout.getvalue())
        self.assertNotIn(ACCESS, stdout.getvalue())

    def test_main_sanitizes_remote_failure(self) -> None:
        secret = "synthetic-remote-secret-do-not-print"
        client = Mock()
        client.check_account.side_effect = PrivateApiError("safe remote failure")
        stderr = io.StringIO()
        with patch.object(provision, "PRIVATE_DIR", self.root / "account"), patch.object(
            provision, "exclusive_session", side_effect=lambda _directory: contextlib.nullcontext(),
        ), patch.object(provision, "PrivateWhoopClient", return_value=client), patch.object(
            provision, "ready_session", return_value=self.tokens,
        ), contextlib.redirect_stderr(stderr):
            result = provision.main(["--output", str(self.root / (secret + ".png"))])
        self.assertEqual(1, result)
        self.assertNotIn(secret, stderr.getvalue())
        self.assertEqual("safe remote failure\n", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
