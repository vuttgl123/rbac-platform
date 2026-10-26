#!/usr/bin/env python3
"""Print a PostgreSQL SCRAM-SHA-256 password verifier for a password read from stdin.

Usage:
    python3 scripts/db/scram_verifier.py                 # prompts (no echo) when stdin is a terminal
    printf '%s' "$DB_APP_PASSWORD" | python3 scripts/db/scram_verifier.py

Output (one line, RFC 5802 / RFC 7677 as stored by PostgreSQL in pg_authid.rolpassword):
    SCRAM-SHA-256$4096:<salt_b64>$<StoredKey_b64>:<ServerKey_b64>

Feed it to scripts/db/bootstrap_login_roles.sql so that the cleartext password never reaches the
server. When stdin is not a terminal, one trailing newline is removed. Standard library only.

PostgreSQL normalises passwords with SASLprep before hashing. SASLprep leaves ASCII unchanged, so
this script accepts ASCII passwords only rather than risk a verifier that would never match.
"""

import base64
import getpass
import hashlib
import hmac
import os
import sys

ITERATIONS = 4096
SALT_BYTES = 16


def scram_sha256_verifier(password: str, salt: bytes, iterations: int = ITERATIONS) -> str:
    salted = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
    stored_key = hashlib.sha256(client_key).digest()
    server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()

    def b64(raw: bytes) -> str:
        return base64.b64encode(raw).decode("ascii")

    return f"SCRAM-SHA-256${iterations}:{b64(salt)}${b64(stored_key)}:{b64(server_key)}"


def read_password() -> str:
    if sys.stdin.isatty():
        return getpass.getpass("Password: ")
    data = sys.stdin.read()
    if data.endswith("\r\n"):
        return data[:-2]
    if data.endswith("\n"):
        return data[:-1]
    return data


def main() -> int:
    password = read_password()
    if not password:
        print("scram_verifier: empty password", file=sys.stderr)
        return 1
    if not password.isascii():
        print("scram_verifier: only ASCII passwords are supported (PostgreSQL applies SASLprep)",
              file=sys.stderr)
        return 1
    print(scram_sha256_verifier(password, os.urandom(SALT_BYTES)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
