"""V97: fail-closed direct dependency integrity for model-containing worker image.

Runs at image BUILD time before model bytes are copied. This gate does not
freeze transitive wheels, apt packages, or the base-image digest; those remain
separate approval requirements. No credentials, installed paths or model bytes
are logged.
"""
from __future__ import annotations

import subprocess
import sys
from importlib import metadata

DIRECT_PINS = {
    "catboost": "1.2.10",
    "hmmlearn": "0.3.3",
    "lightgbm": "4.7.0",
}
NOT_IN_WORKER = ("pytest", "ruff", "torch", "xgboost")
PACKAGE_NAME = "aitrading-intelligence"


def validate(
    python_version: tuple[int, int],
    declared_requirements: list[str],
    installed_versions: dict[str, str],
    forbidden_installed: set[str],
    pip_consistent: bool,
) -> None:
    """Require exactly the reviewed direct ML pins and a usable runtime graph."""
    if python_version != (3, 11):
        raise ValueError("unreviewed interpreter")
    normalized = {
        requirement.split(";", 1)[0].replace(" ", "").lower()
        for requirement in declared_requirements
    }
    for name, pinned_version in DIRECT_PINS.items():
        if f"{name}=={pinned_version}" not in normalized:
            raise ValueError("unreviewed direct ML dependency constraint")
        if installed_versions.get(name) != pinned_version:
            raise ValueError("unreviewed installed ML version")
    if forbidden_installed:
        raise ValueError("research or development tools in the worker image")
    if not pip_consistent:
        raise ValueError("broken resolved dependency graph")


def _is_installed(name: str) -> bool:
    try:
        metadata.version(name)
    except metadata.PackageNotFoundError:
        return False
    return True


def verify_release_dependency_posture() -> None:
    """Validate the actual installed image without reporting package metadata."""
    requirements = metadata.requires(PACKAGE_NAME) or []
    versions = {name: metadata.version(name) for name in DIRECT_PINS}
    unwanted = {name for name in NOT_IN_WORKER if _is_installed(name)}
    # Offline only: pip check evaluates installed distribution constraints;
    # captured output is intentionally discarded to avoid leaking local paths.
    result = subprocess.run(
        [sys.executable, "-m", "pip", "check"],
        capture_output=True,
        check=False,
        timeout=45,
    )
    validate(
        sys.version_info[:2], requirements, versions,
        unwanted, result.returncode == 0,
    )


def main() -> int:
    try:
        verify_release_dependency_posture()
    except (ValueError, TypeError, OSError, subprocess.SubprocessError,
            metadata.PackageNotFoundError):
        print("V97 research image dependency posture BLOCKED (sanitized)", file=sys.stderr)
        return 2
    print("V97 direct dependency gate passed; base/transitive locks NOT attested")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
