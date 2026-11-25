"""V98: require immutable official Python base-image digest for research builds.

This syntax and provenance-namespace check does NOT approve a digest, attest
the registry publisher, freeze apt or pip artifacts, or authorize a release.
The CI-only selector binds the actually pulled test image to a single digest.
"""
from __future__ import annotations

import argparse
import json
import re
import sys

CANONICAL_RE = re.compile(r"docker\.io/library/python@sha256:([0-9a-f]{64})\Z")
PULLED_RE = re.compile(
    r"(?:python|library/python|docker\.io/library/python)@sha256:([0-9a-f]{64})\Z"
)
MAX_DIGEST_JSON = 4096


class BaseImageBlocked(ValueError):
    """Unreviewed or mutable base-image reference."""


def verify_reference(reference: str) -> str:
    """Require canonical immutable reference to the Docker Official Python repo."""
    if not isinstance(reference, str) or CANONICAL_RE.fullmatch(reference) is None:
        raise BaseImageBlocked("model release base reference invalid")
    return reference


def select_pulled_reference(repodigests: object) -> str:
    """Normalize Docker's local RepoDigests, refusing untrusted/mixed identities."""
    if not isinstance(repodigests, list) or not repodigests:
        raise BaseImageBlocked("Docker base digest not present")
    selected: set[str] = set()
    for entry in repodigests:
        if not isinstance(entry, str):
            raise BaseImageBlocked("invalid Docker RepoDigests entry")
        match = PULLED_RE.fullmatch(entry)
        if match is None:
            raise BaseImageBlocked("base image repository is not approved")
        selected.add(match.group(1))
    if len(selected) != 1:
        raise BaseImageBlocked("ambiguous Docker base digest")
    return verify_reference("docker.io/library/python@sha256:" + selected.pop())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="V98 research model base digest only")
    parser.add_argument("action", choices=("select", "verify"))
    parser.add_argument("--reference", default="")
    args = parser.parse_args(argv)
    try:
        if args.action == "select":
            if args.reference:
                raise BaseImageBlocked("unexpected selection override")
            raw = sys.stdin.read(MAX_DIGEST_JSON + 1)
            if len(raw) > MAX_DIGEST_JSON:
                raise BaseImageBlocked("Docker metadata too large")
            print(select_pulled_reference(json.loads(raw)))
        else:
            verify_reference(args.reference)
            print("V98 digest-only base syntax checked; release NOT authorized")
    except (BaseImageBlocked, TypeError, json.JSONDecodeError):
        print("V98 research base image digest BLOCKED (sanitized)", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
