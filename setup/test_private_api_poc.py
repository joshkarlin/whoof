import contextlib
import base64
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from uuid import uuid4

import private_api_poc as cli
import whoop_private_api as api


ACCESS = "synthetic-access-token-1234567890"
REFRESH = "synthetic-refresh-token-1234567890"
TOKENS = api.SessionTokens(ACCESS, REFRESH, 4_000_000_000)
BROWSER_ACCESS = "synthetic.eyJleHAiOjQwMDAwMDAwMDB9.unsigned"
BROWSER_TOKENS = api.SessionTokens(BROWSER_ACCESS, REFRESH, 4_000_000_000)
START = "2026-10-25T10:00:00Z"
END = "2026-10-25T11:00:00Z"
RECEIPT = "a5f69af0-c5b2-4e62-a8ca-ed27d0977624"


class PrivateApiPocCliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name) / "private-api"
        self.private_dir = patch.object(cli, "PRIVATE_DIR", self.directory)
        self.private_dir.start()
        self.addCleanup(self.private_dir.stop)
        self.addCleanup(self.temporary.cleanup)

    def write_session(self) -> None:
        api.save_session(self.directory / "session.json", TOKENS)

    def make_draft(self, *, draft_id: str | None = None, status: str = "prepared") -> tuple[str, Path, dict]:
        draft_id = draft_id or str(uuid4())
        payload = api.build_activity("running", START, END, "UTC")
        draft = {
            "version": 1, "id": draft_id, "status": status, "created_at": "synthetic",
            "sport": "running", "start": START, "end": END,
            "timezone": "UTC", "payload": payload,
        }
        path = cli.draft_path(self.directory, draft_id)
        api.write_private_json(path, draft)
        return draft_id, path, draft

    def test_main_login_then_check_only_authenticates_and_reads_account(self) -> None:
        client = Mock()
        client.begin_login.return_value = TOKENS
        with patch.object(cli, "PrivateWhoopClient", return_value=client), patch.object(
            cli, "hidden_input", side_effect=["synthetic@example.invalid", "synthetic-password-not-real"],
        ) as hidden:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = cli.main(["login"])
        self.assertEqual(0, result)
        self.assertEqual(2, hidden.call_count)
        client.begin_login.assert_called_once_with("synthetic@example.invalid", "synthetic-password-not-real")
        client.check_account.assert_called_once_with(TOKENS)
        client.refresh.assert_not_called()
        client.create_activity.assert_not_called()
        self.assertTrue((self.directory / "check.json").exists())

    def test_browser_session_login_checks_account_before_saving_and_skips_password_login(self) -> None:
        client = Mock()
        session_path = self.directory / "session.json"
        checks = []

        def check_before_save(session):
            checks.append((session, session_path.exists()))

        client.check_account.side_effect = check_before_save
        with patch.object(cli, "PrivateWhoopClient", return_value=client), patch.object(
            cli, "hidden_input", return_value=(
                f'{{"access_token":"{BROWSER_ACCESS}",'
                f'"refresh_token":"{REFRESH}"}}'
            ),
        ) as hidden:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = cli.main(["login", "--browser-session"])
        self.assertEqual(0, result)
        hidden.assert_called_once()
        client.begin_login.assert_not_called()
        client.check_account.assert_called_once_with(BROWSER_TOKENS)
        self.assertEqual([(BROWSER_TOKENS, False)], checks)
        client.create_activity.assert_not_called()
        self.assertEqual(BROWSER_TOKENS, api.load_session(session_path))

    def test_browser_clipboard_login_imports_and_verifies_without_echoing_payload(self) -> None:
        client = Mock()
        session_path = self.directory / "session.json"
        payload = (
            f'{{"access_token":"{BROWSER_ACCESS}",'
            f'"refresh_token":"{REFRESH}"}}'
        )
        clipboard = Mock(return_value=payload)
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(cli, "PrivateWhoopClient", return_value=client), patch.object(
            cli, "clipboard_input", clipboard,
        ), patch.object(cli, "hidden_input") as hidden, contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = cli.main(["login", "--browser-clipboard"])
        self.assertEqual(0, result)
        clipboard.assert_called_once_with()
        hidden.assert_not_called()
        client.begin_login.assert_not_called()
        client.check_account.assert_called_once_with(BROWSER_TOKENS)
        client.create_activity.assert_not_called()
        self.assertEqual(BROWSER_TOKENS, api.load_session(session_path))
        self.assertEqual("verified", api.read_private_json(self.directory / "check.json")["result"])
        self.assertNotIn(payload, stdout.getvalue() + stderr.getvalue())
        self.assertNotIn(BROWSER_ACCESS, stdout.getvalue() + stderr.getvalue())

    def test_malformed_browser_clipboard_does_not_check_or_replace_saved_session(self) -> None:
        self.write_session()
        session_path = self.directory / "session.json"
        original = session_path.read_bytes()
        client = Mock()
        with patch.object(cli, "PrivateWhoopClient", return_value=client), patch.object(
            cli, "clipboard_input", return_value='{"access_token":"malformed","refresh_token":"synthetic"}',
        ), patch.object(cli, "hidden_input") as hidden, contextlib.redirect_stderr(io.StringIO()):
            result = cli.main(["login", "--browser-clipboard"])
        self.assertEqual(1, result)
        hidden.assert_not_called()
        client.check_account.assert_not_called()
        self.assertEqual(original, session_path.read_bytes())

    def test_long_browser_clipboard_bypasses_terminal_input_limit(self) -> None:
        claims = base64.urlsafe_b64encode(json.dumps({
            "exp": 4_000_000_000, "synthetic_padding": "x" * 5_000,
        }).encode()).decode().rstrip("=")
        access = f"synthetic.{claims}.unsigned"
        payload = json.dumps({"access_token": access, "refresh_token": REFRESH})
        self.assertGreater(len(payload), 4096)
        client = Mock()
        output = io.StringIO()
        with patch.object(cli, "PrivateWhoopClient", return_value=client), patch.object(
            cli.sys, "platform", "darwin",
        ), patch.object(cli.subprocess, "run", return_value=Mock(stdout=payload)) as read, patch.object(
            cli, "hidden_input", side_effect=AssertionError("Terminal input must not be used"),
        ), contextlib.redirect_stdout(output):
            self.assertEqual(0, cli.main(["login", "--browser-clipboard"]))
        read.assert_called_once()
        client.check_account.assert_called_once_with(api.SessionTokens(access, REFRESH, 4_000_000_000))
        client.create_activity.assert_not_called()
        self.assertNotIn(access, output.getvalue())
        self.assertEqual(access, api.load_session(self.directory / "session.json").access_token)

    def test_browser_clipboard_read_exception_is_safely_reported(self) -> None:
        secret = "synthetic-clipboard-error-secret-not-for-output"
        stdout, stderr = io.StringIO(), io.StringIO()
        client = Mock()
        with patch.object(cli, "PrivateWhoopClient", return_value=client), patch.object(
            cli.subprocess, "run", side_effect=OSError(secret),
        ) as run, patch.object(cli.sys, "platform", "darwin"), patch.object(
            cli, "hidden_input",
        ) as hidden, contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = cli.main(["login", "--browser-clipboard"])
        self.assertEqual(1, result)
        run.assert_called_once_with(
            ["/usr/bin/pbpaste"], capture_output=True, text=True, timeout=5, check=True,
        )
        hidden.assert_not_called()
        client.check_account.assert_not_called()
        self.assertNotIn(secret, stdout.getvalue() + stderr.getvalue())
        self.assertIn("Unable to read the macOS clipboard", stderr.getvalue())

    def test_browser_clipboard_progress_is_flushed_before_account_check(self) -> None:
        class FlushTrackingOutput(io.StringIO):
            flush_count = 0

            def flush(self) -> None:
                self.flush_count += 1
                super().flush()

        output = FlushTrackingOutput()
        client = Mock()

        def check_account(_tokens):
            self.assertIn("account", output.getvalue().lower())
            self.assertGreater(output.flush_count, 0)

        client.check_account.side_effect = check_account
        payload = (
            f'{{"access_token":"{BROWSER_ACCESS}",'
            f'"refresh_token":"{REFRESH}"}}'
        )
        with patch.object(cli, "PrivateWhoopClient", return_value=client), patch.object(
            cli, "clipboard_input", return_value=payload,
        ), contextlib.redirect_stdout(output):
            self.assertEqual(0, cli.main(["login", "--browser-clipboard"]))
        client.check_account.assert_called_once_with(BROWSER_TOKENS)

    def test_browser_session_and_clipboard_flags_are_mutually_exclusive(self) -> None:
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as raised:
            cli.main(["login", "--browser-session", "--browser-clipboard"])
        self.assertEqual(2, raised.exception.code)
        self.assertIn("not allowed with argument", stderr.getvalue())

    def test_invalid_browser_session_preserves_existing_saved_session(self) -> None:
        self.write_session()
        session_path = self.directory / "session.json"
        original = session_path.read_bytes()
        client = Mock()
        with patch.object(cli, "PrivateWhoopClient", return_value=client), patch.object(
            cli, "hidden_input", return_value='{"access_token":"malformed","refresh_token":"synthetic"}',
        ), contextlib.redirect_stderr(io.StringIO()):
            result = cli.main(["login", "--browser-session"])
        self.assertEqual(1, result)
        self.assertEqual(original, session_path.read_bytes())
        client.begin_login.assert_not_called()
        client.check_account.assert_not_called()

    def test_browser_snippet_prints_code_without_network_activity(self) -> None:
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(api, "request_json") as request, contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = cli.main(["browser-snippet"])
        self.assertEqual(0, result)
        self.assertTrue(stdout.getvalue().strip())
        self.assertEqual("", stderr.getvalue())
        request.assert_not_called()

    def test_rejected_browser_session_does_not_replace_saved_credentials(self) -> None:
        self.write_session()
        session_path = self.directory / "session.json"
        original = session_path.read_bytes()
        client = Mock()
        client.check_account.side_effect = api.PrivateApiError("rejected", status=401)
        payload = (
            f'{{"access_token":"{BROWSER_ACCESS}",'
            f'"refresh_token":"{REFRESH}"}}'
        )
        with patch.object(cli, "PrivateWhoopClient", return_value=client), patch.object(
            cli, "hidden_input", return_value=payload,
        ), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, cli.main(["login", "--browser-session"]))
        self.assertEqual(original, session_path.read_bytes())
        client.check_account.assert_called_once_with(BROWSER_TOKENS)
        client.begin_login.assert_not_called()
        client.create_activity.assert_not_called()

    def test_preview_does_not_call_transport(self) -> None:
        client = Mock()
        client.transport = Mock()
        with patch.object(cli, "PrivateWhoopClient", return_value=client):
            result = cli.main([
                "preview", "--sport", "running", "--start", START, "--end", END,
                "--timezone", "UTC",
            ])
        self.assertEqual(0, result)
        client.transport.assert_not_called()
        self.assertEqual(1, len(list((self.directory / "drafts").glob("*.json"))))

    def test_submit_without_confirmation_never_creates_activity(self) -> None:
        self.write_session()
        draft_id, _path, _draft = self.make_draft()
        client = Mock()
        with patch.object(cli, "PrivateWhoopClient", return_value=client):
            result = cli.main(["submit", draft_id])
        self.assertEqual(0, result)
        client.create_activity.assert_not_called()
        client.refresh.assert_not_called()

    def test_confirmed_draft_submits_once_saves_receipt_and_refuses_repeat(self) -> None:
        self.write_session()
        draft_id, path, _draft = self.make_draft()
        client = Mock()
        client.create_activity.return_value = RECEIPT
        with patch.object(cli, "PrivateWhoopClient", return_value=client):
            self.assertEqual(0, cli.main(["submit", draft_id, "--confirm"]))
            self.assertEqual(1, cli.main(["submit", draft_id, "--confirm"]))
        client.create_activity.assert_called_once()
        saved = api.read_private_json(path)
        self.assertEqual("submitted", saved["status"])
        self.assertEqual(RECEIPT, saved["activity_id"])

    def test_uncertain_network_outcome_is_persisted_and_repeat_refused(self) -> None:
        self.write_session()
        draft_id, path, _draft = self.make_draft()
        client = Mock()
        client.create_activity.side_effect = api.SubmissionUncertainError("uncertain")
        with patch.object(cli, "PrivateWhoopClient", return_value=client):
            self.assertEqual(1, cli.main(["submit", draft_id, "--confirm"]))
            self.assertEqual(1, cli.main(["submit", draft_id, "--confirm"]))
        client.create_activity.assert_called_once()
        self.assertEqual("uncertain", api.read_private_json(path)["status"])

    def test_invalid_draft_id_and_traversal_are_rejected(self) -> None:
        for value in ("not-a-uuid", "../../session.json", "../" + str(uuid4())):
            with self.subTest(draft_id=value), self.assertRaises(api.PrivateApiError):
                cli.draft_path(self.directory, value)

    def test_matching_submitted_or_uncertain_draft_blocks_duplicate_payload(self) -> None:
        self.write_session()
        draft_id, _path, draft = self.make_draft()
        client = Mock()
        for status in ("submitted", "uncertain"):
            with self.subTest(status=status):
                other = dict(draft, id=str(uuid4()), status=status)
                api.write_private_json(cli.draft_path(self.directory, other["id"]), other)
                output = io.StringIO()
                with patch.object(cli, "PrivateWhoopClient", return_value=client), contextlib.redirect_stderr(output):
                    result = cli.main(["submit", draft_id, "--confirm"])
                self.assertEqual(1, result)
                self.assertIn("identical local activity", output.getvalue())
        client.create_activity.assert_not_called()

    def test_unexpected_exception_secret_is_never_printed(self) -> None:
        self.write_session()
        draft_id, _path, _draft = self.make_draft()
        secret = "synthetic-unexpected-secret-not-for-output-123"
        client = Mock()
        client.create_activity.side_effect = RuntimeError(secret)
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(cli, "PrivateWhoopClient", return_value=client), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = cli.main(["submit", draft_id, "--confirm"])
        self.assertEqual(1, result)
        self.assertNotIn(secret, stdout.getvalue() + stderr.getvalue())
        self.assertIn("Local operation failed", stderr.getvalue())
        self.assertEqual("sending", api.read_private_json(cli.draft_path(self.directory, draft_id))["status"])


if __name__ == "__main__":
    unittest.main()
