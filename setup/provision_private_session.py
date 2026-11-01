#!/usr/bin/env python3
"""Export the existing local WHOOP account session as a private provisioning QR."""

from __future__ import annotations

import argparse
import math
import os
from pathlib import Path
import sys
import tempfile
import time
from urllib.parse import urlencode

import qrcode
from qrcode.constants import ERROR_CORRECT_L

from private_api_poc import PRIVATE_DIR, PrivateApiError, PrivateWhoopClient, exclusive_session, ready_session
from whoop_private_api import SessionTokens

MAX_TOKEN_LENGTH = 2048
MAX_PAYLOAD_BYTES = 2900


class ProvisionError(Exception):
    """An intentionally non-sensitive local provisioning error."""


def provisioning_uri(tokens: SessionTokens, *, now: float | None = None) -> str:
    current_time = time.time() if now is None else now
    expiry = tokens.expires_at
    if (
        isinstance(expiry, bool)
        or not isinstance(expiry, (int, float))
        or not math.isfinite(expiry)
        or int(expiry) <= current_time + 60
    ):
        raise ProvisionError("Session expiry is too soon to provision.")

    for token in (tokens.access_token, tokens.refresh_token):
        if (
            not isinstance(token, str)
            or not 20 <= len(token) <= MAX_TOKEN_LENGTH
            or any(ord(character) < 0x21 or ord(character) > 0x7E for character in token)
        ):
            raise ProvisionError("Session credentials are not valid for provisioning.")

    uri = "whoof://private-provision?" + urlencode({
        "v": 1,
        "access_token": tokens.access_token,
        "refresh_token": tokens.refresh_token,
        "expires_at": int(expiry),
    })
    if len(uri.encode("utf-8")) > MAX_PAYLOAD_BYTES:
        raise ProvisionError("Session is too large for a provisioning QR.")
    return uri


def write_private_qr(uri: str, output: Path, *, overwrite: bool = False) -> None:
    output = Path(output)
    parent = output.parent
    temporary: Path | None = None
    try:
        parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(prefix=".whoof-provision-", suffix=".png", dir=parent, delete=False) as stream:
            temporary = Path(stream.name)
            os.fchmod(stream.fileno(), 0o600)
            qr = qrcode.QRCode(error_correction=ERROR_CORRECT_L, box_size=8, border=4)
            qr.add_data(uri)
            qr.make(fit=True)
            qr.make_image(fill_color="black", back_color="white").save(stream, format="PNG")
            stream.flush()
            os.fsync(stream.fileno())

        if overwrite:
            os.replace(temporary, output)
            temporary = None
        else:
            os.link(temporary, output)
    except FileExistsError:
        raise ProvisionError("Output already exists; pass --overwrite to replace it.") from None
    except ProvisionError:
        raise
    except Exception:
        raise ProvisionError("Unable to write the private provisioning QR.") from None
    finally:
        if temporary is not None:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Local PNG destination")
    parser.add_argument("--overwrite", action="store_true", help="Replace an existing output file")
    args = parser.parse_args(argv)

    try:
        output = args.output or (PRIVATE_DIR / "whoof-private-provision.png")
        output = output if output.is_absolute() else Path.cwd() / output
        session_file = (PRIVATE_DIR / "session.json").resolve()
        if output.resolve() == session_file:
            raise ProvisionError("Output cannot replace the local account session.")
        with exclusive_session(PRIVATE_DIR):
            client = PrivateWhoopClient()
            tokens = ready_session(client, PRIVATE_DIR)
            client.check_account(tokens)
            uri = provisioning_uri(tokens)
            write_private_qr(uri, output, overwrite=args.overwrite)
    except (ProvisionError, PrivateApiError) as error:
        print(str(error), file=sys.stderr)
        return 1
    except Exception:
        print("Private session provisioning failed.", file=sys.stderr)
        return 1

    print(f"Private provisioning QR saved to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
