from collections import defaultdict
from pathlib import Path
import re


MIGRATION_DIR = (
    Path(__file__).resolve().parents[2]
    / "backend"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
)


def test_flyway_migration_versions_are_unique():
    versions = defaultdict(list)
    for path in MIGRATION_DIR.glob("V*__*.sql"):
        match = re.match(r"V(\d+)__", path.name)
        assert match, f"Invalid Flyway migration filename: {path.name}"
        versions[int(match.group(1))].append(path.name)

    duplicates = {version: names for version, names in versions.items() if len(names) > 1}
    assert not duplicates, f"Duplicate Flyway migration versions: {duplicates}"
