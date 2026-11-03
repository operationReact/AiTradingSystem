from __future__ import annotations

import argparse
import json
import os
from datetime import UTC, datetime
from pathlib import Path

from pydantic import TypeAdapter

from .india_holdout_freeze import IndiaHoldoutFreezePlan
from .registered_trial_audit import (
    RegisteredTrial,
    RegisteredTrialBudget,
    TrialOutcome,
    audit_registered_trials,
    register_trial_budget,
)
from .structured_alpha_tournament import TournamentCandidate


def _read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Research-only, self-declared trial budget freeze and after-holdout audit"
    )
    cmds = parser.add_subparsers(dest="command", required=True)
    register = cmds.add_parser("register")
    register.add_argument("holdout_freeze_plan", type=Path)
    register.add_argument("trials_json", type=Path)
    register.add_argument("output_budget", type=Path)
    register.add_argument("--baseline-id", required=True)
    register.add_argument("--baseline-version", required=True)
    audit = cmds.add_parser("audit")
    audit.add_argument("holdout_freeze_plan", type=Path)
    audit.add_argument("registered_budget", type=Path)
    audit.add_argument("baseline_evidence", type=Path)
    audit.add_argument("trial_outcomes_json", type=Path)
    audit.add_argument("evaluated_candidates_json", type=Path)
    args = parser.parse_args()
    frozen = IndiaHoldoutFreezePlan.model_validate_json(
        args.holdout_freeze_plan.read_text(encoding="utf-8")
    )
    if args.command == "register":
        slots = TypeAdapter(list[RegisteredTrial]).validate_python(
            _read_json(args.trials_json)
        )
        plan = register_trial_budget(
            frozen, slots,
            baseline_candidate_id=args.baseline_id,
            baseline_model_version_ref=args.baseline_version,
            registered_at=datetime.now(UTC),
        )
        # Caller should independently timestamp the digest BEFORE collecting
        # the next holdout. Never overwrite a preexisting locked trial budget.
        handle = os.open(
            str(args.output_budget), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600
        )
        with os.fdopen(handle, "w", encoding="utf-8") as output:
            json.dump(plan.model_dump(mode="json"), output, sort_keys=True)
            output.write("\n")
        print(plan.budget_fingerprint)
        return

    outcomes = TypeAdapter(list[TrialOutcome]).validate_python(
        _read_json(args.trial_outcomes_json)
    )
    candidates = TypeAdapter(list[TournamentCandidate]).validate_python(
        _read_json(args.evaluated_candidates_json)
    )
    audit_report = audit_registered_trials(
        frozen,
        RegisteredTrialBudget.model_validate_json(
            args.registered_budget.read_text(encoding="utf-8")
        ),
        TournamentCandidate.model_validate(_read_json(args.baseline_evidence)),
        outcomes,
        candidates,
        evaluation_started_at=datetime.now(UTC),
    )
    print(json.dumps(audit_report.model_dump(mode="json"), sort_keys=True))


if __name__ == "__main__":
    main()
