#!/usr/bin/env python3
"""Local-only WHOOP private-API experiment. Start with `login`, then `check`."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import getpass
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from uuid import UUID, uuid4
import warnings

from whoop_private_api import (
    ACTIVITY_URL, SPORTS, LoginChallenge, PrivateApiError, PrivateWhoopClient,
    SessionTokens, SubmissionUncertainError, build_activity, load_session,
    parse_browser_session, private_directory, read_private_json, save_session, write_private_json,
)

PRIVATE_DIR = Path(__file__).resolve().parents[1] / "local" / "private-api"
BROWSER_SNIPPET = """(() => {
  if (location.protocol !== 'https:' || location.hostname !== 'app.whoop.com') {
    throw new Error('Run this only on the signed-in https://app.whoop.com page.');
  }
  const cookies = new Map(document.cookie.split(';').map(part => {
    const separator = part.indexOf('=');
    return [part.slice(0, separator).trim(), part.slice(separator + 1)];
  }));
  const access = decodeURIComponent(cookies.get('whoop-auth-token') || '');
  const refresh = decodeURIComponent(cookies.get('whoop-auth-refresh-token') || '');
  if (!access || !refresh) {
    throw new Error('WHOOP session cookies are unavailable. Sign in and try again.');
  }
  if (typeof copy !== 'function') {
    throw new Error('Run this in the browser Developer Tools Console.');
  }
  copy(JSON.stringify({access_token: access, refresh_token: refresh}));
  return 'Session copied. Paste it into the hidden terminal prompt, not into chat.';
})()"""


def timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@contextmanager
def exclusive_session(directory: Path):
    import fcntl

    private_directory(directory)
    descriptor = os.open(directory / "session.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w") as lock:
        os.fchmod(lock.fileno(), 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise PrivateApiError("Another private-API command is running. Finish it first.") from None
        yield


def hidden_input(prompt: str) -> str:
    if not sys.stdin.isatty():
        raise PrivateApiError("Run login in an interactive terminal. Do not pass credentials as arguments or pipe them.")
    with warnings.catch_warnings():
        warnings.simplefilter("error", getpass.GetPassWarning)
        try:
            return getpass.getpass(prompt)
        except getpass.GetPassWarning:
            raise PrivateApiError("Unable to disable terminal echo; login cancelled.") from None


def clipboard_input() -> str:
    if sys.platform != "darwin":
        raise PrivateApiError("Clipboard import currently requires macOS.")
    try:
        result = subprocess.run(
            ["/usr/bin/pbpaste"], capture_output=True, text=True, timeout=5, check=True,
        )
    except (OSError, subprocess.SubprocessError, UnicodeError):
        raise PrivateApiError("Unable to read the macOS clipboard. Copy a fresh WHOOP session and try again.") from None
    return result.stdout


def login(
    client: PrivateWhoopClient, directory: Path, *,
    browser_session: bool = False, browser_clipboard: bool = False,
) -> None:
    if browser_session or browser_clipboard:
        if browser_clipboard:
            print("Reading WHOOP session from the local clipboard...", flush=True)
            payload = clipboard_input()
        else:
            print("Import your own signed-in WHOOP browser session. Input is hidden.", flush=True)
            print("For long sessions on macOS, use login --browser-clipboard instead of pasting.", flush=True)
            payload = hidden_input("Paste WHOOP browser session: ")
        tokens = parse_browser_session(payload)
        print("Session format accepted. Checking WHOOP account (30-second network timeout)...", flush=True)
        # Validate against WHOOP before replacing any previously stored session.
        client.check_account(tokens)
        save_session(directory / "session.json", tokens)
        return
    print("WHOOP account login. Email, password, and verification code input are hidden.")
    email = hidden_input("WHOOP email: ")
    password = hidden_input("WHOOP password: ")
    try:
        result = client.begin_login(email, password)
    finally:
        password = ""
    for _ in range(3):
        if isinstance(result, SessionTokens):
            save_session(directory / "session.json", result)
            return
        if not isinstance(result, LoginChallenge):
            raise PrivateApiError("WHOOP returned an unsupported login result.")
        prompts = {
            "SMS_MFA": "SMS verification code: ",
            "SOFTWARE_TOKEN_MFA": "Authenticator verification code: ",
            "EMAIL_OTP": "Email verification code: ",
        }
        code = hidden_input(prompts[result.name])
        result = client.complete_login(result, code)
        code = ""
    if isinstance(result, SessionTokens):
        save_session(directory / "session.json", result)
        return
    raise PrivateApiError("WHOOP requested too many verification steps; login cancelled.")


def ready_session(client: PrivateWhoopClient, directory: Path, *, force_refresh: bool = False) -> SessionTokens:
    path = directory / "session.json"
    tokens = load_session(path)
    if force_refresh or tokens.expires_at <= time.time() + 60:
        tokens = client.refresh(tokens)
        save_session(path, tokens)
    return tokens


def record_check_success(directory: Path) -> None:
    write_private_json(directory / "check.json", {
        "version": 1, "result": "verified", "checked_at": timestamp(),
        "operation": "authenticated_account_read",
    })
    print("Authenticated WHOOP account read succeeded. No activities were created.", flush=True)


def check(client: PrivateWhoopClient, directory: Path, *, force_refresh: bool = False) -> None:
    stored = load_session(directory / "session.json")
    refreshed = force_refresh or stored.expires_at <= time.time() + 60
    print("Checking saved WHOOP account session (30-second network timeout per request)...", flush=True)
    tokens = ready_session(client, directory, force_refresh=force_refresh)
    try:
        client.check_account(tokens)
    except PrivateApiError as error:
        if error.status != 401 or refreshed:
            raise
        tokens = client.refresh(tokens)
        save_session(directory / "session.json", tokens)
        client.check_account(tokens)
    record_check_success(directory)


def draft_path(directory: Path, draft_id: str) -> Path:
    try:
        canonical = str(UUID(draft_id))
    except (ValueError, TypeError, AttributeError):
        raise PrivateApiError("Use the draft ID printed by preview.") from None
    return directory / "drafts" / f"{canonical}.json"


def load_draft(directory: Path, draft_id: str) -> tuple[Path, dict]:
    path = draft_path(directory, draft_id)
    draft = read_private_json(path)
    if draft.get("version") != 1 or draft.get("id") != path.stem:
        raise PrivateApiError("Invalid local activity draft.")
    try:
        rebuilt = build_activity(draft["sport"], draft["start"], draft["end"], draft["timezone"])
    except (KeyError, TypeError):
        raise PrivateApiError("Invalid local activity draft.") from None
    if draft.get("payload") != rebuilt:
        raise PrivateApiError("The draft no longer matches its preview. Prepare a new draft.")
    return path, draft


def show_preview(draft: dict) -> None:
    print(f"Draft: {draft['id']} ({draft['status']})")
    print(f"POST {ACTIVITY_URL}")
    print(f"Timezone: {draft['timezone']}")
    print(json.dumps(draft["payload"], indent=2))


def prepare(args, directory: Path) -> None:
    payload = build_activity(args.sport, args.start, args.end, args.timezone)
    draft = {
        "version": 1, "id": str(uuid4()), "status": "prepared", "created_at": timestamp(),
        "sport": args.sport, "start": args.start, "end": args.end,
        "timezone": args.timezone, "payload": payload,
    }
    write_private_json(draft_path(directory, draft["id"]), draft)
    show_preview(draft)
    print("Saved locally. No request was sent.")


def submit(client: PrivateWhoopClient, directory: Path, draft_id: str, *, confirmed: bool) -> None:
    path, draft = load_draft(directory, draft_id)
    show_preview(draft)
    if not confirmed:
        print("Preview only. No request was sent. Submission requires --confirm.")
        return
    if draft.get("status") != "prepared":
        raise PrivateApiError("This draft has already been attempted. Check WHOOP; it cannot be sent again.")
    for other_path in path.parent.glob("*.json"):
        if other_path == path:
            continue
        other = read_private_json(other_path)
        if other.get("payload") == draft["payload"] and other.get("status") in {"sending", "submitted", "uncertain"}:
            raise PrivateApiError("An identical local activity has already been attempted. Check WHOOP before continuing.")
    tokens = ready_session(client, directory)
    # Persist the attempted state BEFORE the POST, including for process termination.
    draft.update(status="sending", attempted_at=timestamp())
    write_private_json(path, draft)
    try:
        receipt = client.create_activity(tokens, draft["payload"], draft["timezone"])
    except PrivateApiError as error:
        draft["status"] = "uncertain" if isinstance(error, SubmissionUncertainError) else "rejected"
        write_private_json(path, draft)
        raise
    draft.update(status="submitted", activity_id=receipt, submitted_at=timestamp())
    write_private_json(path, draft)
    print("Activity submitted once. Receipt saved locally; check the official WHOOP app.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    signing_in = commands.add_parser("login", help="Sign in with hidden prompts and verify a read-only account request")
    browser_input = signing_in.add_mutually_exclusive_group()
    browser_input.add_argument("--browser-session", action="store_true", help="Import a session with a hidden terminal prompt (long pastes can exceed terminal limits)")
    browser_input.add_argument("--browser-clipboard", action="store_true", help="Import the WHOOP session directly from the macOS clipboard, without terminal pasting")
    commands.add_parser("browser-snippet", help="Print code to copy your WHOOP session into the clipboard locally")
    checking = commands.add_parser("check", help="Verify the saved session without creating an activity")
    checking.add_argument("--refresh", action="store_true", help="Test account-session refresh before the read")
    preparing = commands.add_parser("preview", help="Prepare a local activity draft, without network access")
    preparing.add_argument("--sport", required=True, choices=sorted(SPORTS))
    preparing.add_argument("--start", required=True, help="ISO timestamp including UTC offset")
    preparing.add_argument("--end", required=True, help="ISO timestamp including UTC offset")
    preparing.add_argument("--timezone", required=True, help="IANA timezone, such as Europe/London")
    sending = commands.add_parser("submit", help="Preview an existing draft, or submit once with --confirm")
    sending.add_argument("draft_id")
    sending.add_argument("--confirm", action="store_true", help="Explicitly send this exact draft once")
    args = parser.parse_args(argv)
    if args.command == "browser-snippet":
        print(BROWSER_SNIPPET)
        return 0
    try:
        with exclusive_session(PRIVATE_DIR):
            client = PrivateWhoopClient()
            if args.command in {"login", "check"}:
                write_private_json(PRIVATE_DIR / "check.json", {
                    "version": 1, "result": "not_verified", "checked_at": timestamp(),
                })
                if args.command == "login":
                    login(
                        client, PRIVATE_DIR, browser_session=args.browser_session,
                        browser_clipboard=args.browser_clipboard,
                    )
                if args.command == "login" and (args.browser_session or args.browser_clipboard):
                    record_check_success(PRIVATE_DIR)
                else:
                    check(client, PRIVATE_DIR, force_refresh=getattr(args, "refresh", False))
            elif args.command == "preview":
                prepare(args, PRIVATE_DIR)
            elif args.command == "submit":
                submit(client, PRIVATE_DIR, args.draft_id, confirmed=args.confirm)
    except (EOFError, KeyboardInterrupt):
        print("Cancelled. Any draft marked sending must be checked in WHOOP before another attempt.", file=sys.stderr)
        return 1
    except PrivateApiError as error:
        print(str(error), file=sys.stderr)
        return 1
    except Exception:
        # Never dump an unexpected exception that could contain an account payload.
        print("Local operation failed. No automatic submission retry was made.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
