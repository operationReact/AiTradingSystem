#!/usr/bin/env python3
"""Bind the Actions Gemini key to the exact PAPER host without sending plaintext over SSM.

The host creates an ephemeral RSA key pair. Only its public key leaves the host;
the Actions secret is encrypted with RSA-OAEP-SHA256 before it is placed in an
SSM command. The host writes it atomically to mode-0600 runtime.env. No key,
plaintext command output, or provider response is logged or uploaded.
"""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time

ACCOUNT = "098859530205"
REGION = "ap-south-1"
INSTANCE = "i-09c01b470fb39b85a"
ROOT = "/opt/aitradingsystem-paper"
MODEL = "gemini-3.5-flash-lite"
KEY_RE = re.compile(r"[A-Za-z0-9_-]{20,256}")
NONCE_RE = re.compile(r"[0-9a-f]{32}")
PUBLIC_MARKER = "MINTTICK_GEMINI_BIND_PUBLIC_KEY:"
STORED_MARKER = "MINTTICK_GEMINI_BIND_STORED:true"


class BindError(RuntimeError):
    pass


def validate_key(value: str) -> None:
    if not isinstance(value, str) or not KEY_RE.fullmatch(value):
        raise BindError("Gemini key is absent or has an unsupported format")


def bootstrap_command(nonce: str) -> str:
    if not NONCE_RE.fullmatch(nonce):
        raise BindError("invalid ephemeral host key identifier")
    state = f"{ROOT}/.gemini-keybind-{nonce}"
    return f"""set -euo pipefail
umask 077
stage=identity
trap 'rc=$?; printf "MINTTICK_GEMINI_BIND_FAILED:%s\\n" "$stage" >&2; exit "$rc"' ERR
test "$(id -u)" -eq 0
test "$(uname -m)" = "aarch64"
test -d '{ROOT}' && test ! -L '{ROOT}'
stage=keygen
install -d -m 0700 '{state}'
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out '{state}/private.pem' >/dev/null 2>&1
openssl pkey -in '{state}/private.pem' -pubout -out '{state}/public.pem' 2>/dev/null
stage=public_key
pub="$(base64 -w0 '{state}/public.pem')"
printf '{PUBLIC_MARKER}{nonce}:%s\\n' "$pub"
stage=complete
"""


def install_command(nonce: str, ciphertext_b64: str) -> str:
    # RSA-3072 OAEP ciphertext is exactly 384 bytes, so base64 has 512 chars.
    if not NONCE_RE.fullmatch(nonce) or not re.fullmatch(r"[A-Za-z0-9+/=]{512}", ciphertext_b64):
        raise BindError("invalid encrypted secret envelope")
    state = f"{ROOT}/.gemini-keybind-{nonce}"
    return f"""set -euo pipefail
umask 077
stage=identity
trap 'rc=$?; rm -rf -- '{state}'; printf "MINTTICK_GEMINI_BIND_FAILED:%s\\n" "$stage" >&2; exit "$rc"' ERR
test "$(id -u)" -eq 0
test "$(uname -m)" = "aarch64"
test -d '{state}' && test ! -L '{state}'
test "$(stat -c '%u:%a' '{state}')" = '0:700'
stage=decrypt
printf '%s' '{ciphertext_b64}' | base64 -d > '{state}/ciphertext.bin'
openssl pkeyutl -decrypt -inkey '{state}/private.pem' \\
  -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 \\
  -in '{state}/ciphertext.bin' -out '{state}/gemini.key' 2>/dev/null
chmod 0600 '{state}/gemini.key'
stage=runtime_write
python3 - '{ROOT}/runtime.env' '{state}/gemini.key' <<'PY'
import os
from pathlib import Path
import re
import stat
import sys
import tempfile

runtime = Path(sys.argv[1])
key_path = Path(sys.argv[2])
for path in (runtime, key_path):
    info = os.lstat(path)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit(31)
key = key_path.read_text(encoding="ascii")
if not re.fullmatch(r"[A-Za-z0-9_-]{20,256}", key):
    raise SystemExit(32)
lines = runtime.read_text(encoding="utf-8").splitlines()
names = {"GEMINI_API_KEY", "NEWS_ANALYSIS_PROVIDER", "GEMINI_MODEL"}
seen = set()
kept = []
for line in lines:
    name, sep, _ = line.partition("=")
    if sep and name in names:
        if name in seen:
            raise SystemExit(33)
        seen.add(name)
    else:
        kept.append(line)
kept.extend([
    "NEWS_ANALYSIS_PROVIDER=GEMINI",
    "GEMINI_MODEL={MODEL}",
    "GEMINI_API_KEY=" + key,
])
fd, temporary = tempfile.mkstemp(prefix=".runtime.env.gemini.", dir=str(runtime.parent))
try:
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as output:
        output.write("\n".join(kept) + "\n")
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, runtime)
except BaseException:
    try:
        os.unlink(temporary)
    except FileNotFoundError:
        pass
    raise
PY
stage=cleanup
rm -rf -- '{state}'
printf '{STORED_MARKER}\\n'
stage=complete
"""


def aws_json(*args: str) -> dict:
    try:
        process = subprocess.run(
            ["aws", *args, "--region", REGION, "--output", "json", "--no-cli-pager"],
            capture_output=True, text=True, timeout=60, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise BindError("AWS CLI request failed") from exc
    if process.returncode:
        raise BindError("AWS request rejected by the existing narrow SSM role")
    try:
        value = json.loads(process.stdout)
    except ValueError as exc:
        raise BindError("AWS returned invalid JSON") from exc
    if not isinstance(value, dict):
        raise BindError("AWS returned an unexpected response")
    return value


def run_ssm(command: str, timeout_seconds: int = 120) -> str:
    if len(command.encode("utf-8")) > 6000:
        raise BindError("SSM command exceeds the fixed size limit")
    request = aws_json(
        "ssm", "send-command", "--document-name", "AWS-RunShellScript",
        "--instance-ids", INSTANCE, "--timeout-seconds", str(timeout_seconds),
        "--comment", "MintTick Gemini secret bind (encrypted envelope)",
        "--parameters", json.dumps({"commands": [command]}))
    command_id = request.get("Command", {}).get("CommandId")
    if not isinstance(command_id, str) or not re.fullmatch(r"[0-9a-f-]{36}", command_id):
        raise BindError("SSM did not return a valid command id")
    for attempt in range(45):
        time.sleep(2)
        try:
            result = aws_json("ssm", "get-command-invocation", "--command-id",
                              command_id, "--instance-id", INSTANCE)
        except BindError:
            if attempt < 5:
                continue
            raise
        status = result.get("Status")
        if status == "Success":
            return str(result.get("StandardOutputContent") or "")
        if status in {"Failed", "TimedOut", "Cancelled", "Cancelling"}:
            stderr = str(result.get("StandardErrorContent") or "")
            match = re.search(r"MINTTICK_GEMINI_BIND_FAILED:(identity|keygen|public_key|decrypt|runtime_write|cleanup|complete)", stderr)
            if match:
                raise BindError("host binding failed at " + match.group(1))
            raise BindError("host binding did not pass its safe stage checks")
        if status not in {"Pending", "InProgress", "Delayed"}:
            raise BindError("SSM returned an unexpected status")
    raise BindError("SSM command timed out")


def encrypt_for_host(public_key_b64: str, secret: str) -> str:
    try:
        pem = base64.b64decode(public_key_b64, validate=True)
    except ValueError as exc:
        raise BindError("invalid host public key") from exc
    with tempfile.TemporaryDirectory(prefix="minttick-gemini-bind-") as directory:
        os.chmod(directory, 0o700)
        public_path = Path(directory) / "public.pem"
        plaintext_path = Path(directory) / "secret"
        ciphertext_path = Path(directory) / "ciphertext"
        public_path.write_bytes(pem)
        plaintext_path.write_text(secret, encoding="ascii")
        os.chmod(public_path, 0o600)
        os.chmod(plaintext_path, 0o600)
        try:
            process = subprocess.run(
                ["openssl", "pkeyutl", "-encrypt", "-pubin", "-inkey", str(public_path),
                 "-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256",
                 "-in", str(plaintext_path), "-out", str(ciphertext_path)],
                capture_output=True, text=True, timeout=20, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise BindError("local encryption failed") from exc
        if process.returncode:
            raise BindError("local encryption failed")
        encrypted = ciphertext_path.read_bytes()
        if len(encrypted) != 384:
            raise BindError("unexpected encrypted envelope size")
        return base64.b64encode(encrypted).decode("ascii")


def bind(secret: str) -> None:
    validate_key(secret)
    os.environ["AWS_REGION"] = REGION
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import verify_ssm_release as release
    release.verify_aws()
    nonce = secrets.token_hex(16)
    bootstrap = run_ssm(bootstrap_command(nonce))
    marker = re.compile(
        rf"(?m)^{re.escape(PUBLIC_MARKER)}{nonce}:([A-Za-z0-9+/=]+)$")
    match = marker.search(bootstrap)
    if not match:
        raise BindError("host key generation did not return the allowlisted public key")
    encrypted = encrypt_for_host(match.group(1), secret)
    stored = run_ssm(install_command(nonce, encrypted))
    if STORED_MARKER not in stored.splitlines():
        raise BindError("host did not confirm the safe runtime binding")
    print("Gemini key bound to exact PAPER host; plaintext was not sent through SSM", flush=True)


def main(argv: list[str]) -> int:
    try:
        if argv != ["runner"]:
            raise BindError("unsupported operation")
        bind(os.environ.get("GEMINI_API_KEY", ""))
        return 0
    except BindError as exc:
        print("GEMINI_SECRET_BIND_FAILED: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
