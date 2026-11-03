from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .fingerprints import canonical_fingerprint
from .model_registry import ModelFamily, ModelGovernanceCandidate
from .promotion_governance import PromotionGovernanceDecision

CONTROL_PLANE_VERSION = "model-governance-control-plane.v1"
Hash64 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
LifecycleState = Literal[
    "REGISTERED",
    "SHADOW",
    "ELIGIBLE",
    "CHAMPION",
    "QUARANTINED",
    "DISABLED",
]

_ALLOWED_TRANSITIONS: dict[LifecycleState, frozenset[LifecycleState]] = {
    "REGISTERED": frozenset({"SHADOW", "QUARANTINED", "DISABLED"}),
    "SHADOW": frozenset({"ELIGIBLE", "QUARANTINED", "DISABLED"}),
    "ELIGIBLE": frozenset({"SHADOW", "QUARANTINED", "DISABLED"}),
    "CHAMPION": frozenset({"ELIGIBLE", "QUARANTINED", "DISABLED"}),
    "QUARANTINED": frozenset({"SHADOW", "DISABLED"}),
    "DISABLED": frozenset(),
}


class ModelControlPlaneEntry(BaseModel):
    """Immutable governance state for one registered model artifact.

    ``live_eligible`` allows selection by an execution workflow. It never gives
    Python capital authority; deterministic Java risk and execution controls
    remain authoritative.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["model-governance-control-plane.v1"] = CONTROL_PLANE_VERSION
    registry_fingerprint: Hash64
    model_family: ModelFamily
    model_version: Annotated[str, Field(min_length=1, max_length=128)]
    challenger_of: Hash64 | None = None
    lifecycle_state: LifecycleState
    evidence_fingerprint: Hash64
    config_fingerprint: Hash64
    updated_at: datetime
    enabled: bool
    quarantined: bool
    live_eligible: bool
    can_authorize_capital: Literal[False] = False
    entry_fingerprint: Hash64

    @field_validator("updated_at")
    @classmethod
    def updated_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("updated_at must be timezone-aware")
        return value

    @model_validator(mode="after")
    def state_flags_must_match(self) -> ModelControlPlaneEntry:
        expected_enabled = self.lifecycle_state not in {"QUARANTINED", "DISABLED"}
        if self.enabled != expected_enabled:
            raise ValueError("enabled flag does not match lifecycle state")
        if self.quarantined != (self.lifecycle_state == "QUARANTINED"):
            raise ValueError("quarantined flag does not match lifecycle state")
        if self.live_eligible != (self.lifecycle_state == "CHAMPION"):
            raise ValueError("only the champion may be live eligible")
        return self


class GovernanceTransitionAudit(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["model-governance-control-plane.v1"] = CONTROL_PLANE_VERSION
    registry_fingerprint: Hash64
    from_state: LifecycleState
    to_state: LifecycleState
    occurred_at: datetime
    actor: Annotated[str, Field(min_length=1, max_length=128)]
    reason: Annotated[str, Field(min_length=1, max_length=512)]
    evidence_fingerprint: Hash64
    config_fingerprint: Hash64
    promotion_decision_fingerprint: Hash64 | None = None
    explicit_activation_approved: bool = False
    audit_fingerprint: Hash64

    @field_validator("occurred_at")
    @classmethod
    def occurred_at_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("occurred_at must be timezone-aware")
        return value


class ModelGovernanceControlPlane(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contract_version: Literal["model-governance-control-plane.v1"] = CONTROL_PLANE_VERSION
    as_of: datetime
    entries: list[ModelControlPlaneEntry] = Field(min_length=1, max_length=1_000)
    audit_log: list[GovernanceTransitionAudit] = Field(
        default_factory=list,
        max_length=100_000,
    )
    snapshot_fingerprint: Hash64
    promotion_allowed: Literal[False] = False
    can_authorize_capital: Literal[False] = False

    @field_validator("as_of")
    @classmethod
    def as_of_must_be_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("as_of must be timezone-aware")
        return value

    @model_validator(mode="after")
    def validate_snapshot(self) -> ModelGovernanceControlPlane:
        registry_ids = [entry.registry_fingerprint for entry in self.entries]
        if len(registry_ids) != len(set(registry_ids)):
            raise ValueError("control plane cannot contain duplicate registry entries")
        if sum(entry.lifecycle_state == "CHAMPION" for entry in self.entries) > 1:
            raise ValueError("control plane may contain at most one champion")
        if any(entry.updated_at > self.as_of for entry in self.entries):
            raise ValueError("control plane cannot contain future model state")
        if any(event.occurred_at > self.as_of for event in self.audit_log):
            raise ValueError("control plane cannot contain future audit events")

        known_ids = set(registry_ids)
        if any(event.registry_fingerprint not in known_ids for event in self.audit_log):
            raise ValueError("audit log references a model outside the control plane")

        ordered = sorted(self.entries, key=lambda entry: entry.registry_fingerprint)
        if self.entries != ordered:
            raise ValueError("control plane entries must use canonical registry order")
        return self


def _state_flags(state: LifecycleState) -> tuple[bool, bool, bool]:
    enabled = state not in {"QUARANTINED", "DISABLED"}
    quarantined = state == "QUARANTINED"
    live_eligible = state == "CHAMPION"
    return enabled, quarantined, live_eligible


def _build_entry(
    *,
    registry_fingerprint: str,
    model_family: ModelFamily,
    model_version: str,
    challenger_of: str | None,
    lifecycle_state: LifecycleState,
    evidence_fingerprint: str,
    config_fingerprint: str,
    updated_at: datetime,
) -> ModelControlPlaneEntry:
    enabled, quarantined, live_eligible = _state_flags(lifecycle_state)
    payload = {
        "contract_version": CONTROL_PLANE_VERSION,
        "registry_fingerprint": registry_fingerprint,
        "model_family": model_family,
        "model_version": model_version,
        "challenger_of": challenger_of,
        "lifecycle_state": lifecycle_state,
        "evidence_fingerprint": evidence_fingerprint,
        "config_fingerprint": config_fingerprint,
        "updated_at": updated_at.isoformat(),
        "enabled": enabled,
        "quarantined": quarantined,
        "live_eligible": live_eligible,
        "can_authorize_capital": False,
    }
    return ModelControlPlaneEntry(
        **payload,
        entry_fingerprint=canonical_fingerprint(payload),
    )


def _build_audit(
    *,
    registry_fingerprint: str,
    from_state: LifecycleState,
    to_state: LifecycleState,
    occurred_at: datetime,
    actor: str,
    reason: str,
    evidence_fingerprint: str,
    config_fingerprint: str,
    promotion_decision_fingerprint: str | None = None,
    explicit_activation_approved: bool = False,
) -> GovernanceTransitionAudit:
    payload = {
        "contract_version": CONTROL_PLANE_VERSION,
        "registry_fingerprint": registry_fingerprint,
        "from_state": from_state,
        "to_state": to_state,
        "occurred_at": occurred_at.isoformat(),
        "actor": actor,
        "reason": reason,
        "evidence_fingerprint": evidence_fingerprint,
        "config_fingerprint": config_fingerprint,
        "promotion_decision_fingerprint": promotion_decision_fingerprint,
        "explicit_activation_approved": explicit_activation_approved,
    }
    return GovernanceTransitionAudit(
        **payload,
        audit_fingerprint=canonical_fingerprint(payload),
    )


def _build_snapshot(
    *,
    entries: Sequence[ModelControlPlaneEntry],
    audit_log: Sequence[GovernanceTransitionAudit],
    as_of: datetime,
) -> ModelGovernanceControlPlane:
    ordered = sorted(entries, key=lambda entry: entry.registry_fingerprint)
    payload = {
        "contract_version": CONTROL_PLANE_VERSION,
        "as_of": as_of.isoformat(),
        "entries": [entry.model_dump(mode="json") for entry in ordered],
        "audit_log": [event.model_dump(mode="json") for event in audit_log],
        "promotion_allowed": False,
        "can_authorize_capital": False,
    }
    return ModelGovernanceControlPlane(
        as_of=as_of,
        entries=ordered,
        audit_log=list(audit_log),
        snapshot_fingerprint=canonical_fingerprint(payload),
    )


def initialize_model_governance_control_plane(
    candidates: Sequence[ModelGovernanceCandidate],
    *,
    governance_config_fingerprint: str,
    as_of: datetime,
) -> ModelGovernanceControlPlane:
    if as_of.tzinfo is None or as_of.utcoffset() is None:
        raise ValueError("as_of must be timezone-aware")
    if not candidates:
        raise ValueError("control plane requires at least one governance candidate")

    registry_ids = [candidate.registry_fingerprint for candidate in candidates]
    if len(registry_ids) != len(set(registry_ids)):
        raise ValueError("control plane cannot enroll duplicate registry candidates")

    entries: list[ModelControlPlaneEntry] = []
    for candidate in candidates:
        if candidate.registered_at > as_of:
            raise ValueError("control plane cannot enroll a future registry candidate")
        if candidate.promotion_allowed or candidate.can_authorize_capital:
            raise ValueError("registry candidates cannot carry promotion or capital authority")

        evidence_fingerprint = canonical_fingerprint(
            {
                "evaluation_fingerprint": candidate.evaluation_fingerprint,
                "monte_carlo_evidence_fingerprint": (
                    candidate.monte_carlo_evidence_fingerprint
                ),
            }
        )
        entries.append(
            _build_entry(
                registry_fingerprint=candidate.registry_fingerprint,
                model_family=candidate.model_family,
                model_version=candidate.model_version,
                challenger_of=candidate.challenger_of,
                lifecycle_state="REGISTERED",
                evidence_fingerprint=evidence_fingerprint,
                config_fingerprint=governance_config_fingerprint,
                updated_at=candidate.registered_at,
            )
        )
    return _build_snapshot(entries=entries, audit_log=[], as_of=as_of)


def _find_entry(
    control_plane: ModelGovernanceControlPlane,
    registry_fingerprint: str,
) -> ModelControlPlaneEntry:
    matches = [
        entry
        for entry in control_plane.entries
        if entry.registry_fingerprint == registry_fingerprint
    ]
    if len(matches) != 1:
        raise ValueError("registry fingerprint must identify exactly one controlled model")
    return matches[0]


def transition_model_lifecycle(
    control_plane: ModelGovernanceControlPlane,
    *,
    registry_fingerprint: str,
    target_state: LifecycleState,
    evidence_fingerprint: str,
    config_fingerprint: str,
    occurred_at: datetime,
    actor: str,
    reason: str,
) -> ModelGovernanceControlPlane:
    if target_state == "CHAMPION":
        raise ValueError("CHAMPION activation requires explicit promotion approval")
    if occurred_at.tzinfo is None or occurred_at.utcoffset() is None:
        raise ValueError("occurred_at must be timezone-aware")
    if occurred_at < control_plane.as_of:
        raise ValueError("governance transitions must be monotonic in time")

    current = _find_entry(control_plane, registry_fingerprint)
    if target_state not in _ALLOWED_TRANSITIONS[current.lifecycle_state]:
        raise ValueError(
            f"illegal lifecycle transition: {current.lifecycle_state} -> {target_state}"
        )

    updated = _build_entry(
        registry_fingerprint=current.registry_fingerprint,
        model_family=current.model_family,
        model_version=current.model_version,
        challenger_of=current.challenger_of,
        lifecycle_state=target_state,
        evidence_fingerprint=evidence_fingerprint,
        config_fingerprint=config_fingerprint,
        updated_at=occurred_at,
    )
    audit = _build_audit(
        registry_fingerprint=current.registry_fingerprint,
        from_state=current.lifecycle_state,
        to_state=target_state,
        occurred_at=occurred_at,
        actor=actor,
        reason=reason,
        evidence_fingerprint=evidence_fingerprint,
        config_fingerprint=config_fingerprint,
    )
    entries = [
        updated if entry.registry_fingerprint == registry_fingerprint else entry
        for entry in control_plane.entries
    ]
    return _build_snapshot(
        entries=entries,
        audit_log=[*control_plane.audit_log, audit],
        as_of=occurred_at,
    )


def activate_champion(
    control_plane: ModelGovernanceControlPlane,
    *,
    registry_fingerprint: str,
    promotion_decision: PromotionGovernanceDecision,
    explicit_activation_approved: bool,
    occurred_at: datetime,
    actor: str,
    reason: str,
) -> ModelGovernanceControlPlane:
    if not explicit_activation_approved:
        raise ValueError("champion activation requires explicit approval")
    if occurred_at.tzinfo is None or occurred_at.utcoffset() is None:
        raise ValueError("occurred_at must be timezone-aware")
    if occurred_at < control_plane.as_of:
        raise ValueError("governance transitions must be monotonic in time")
    if promotion_decision.decision != "PROMOTION_RECOMMENDED":
        raise ValueError("champion activation requires a promotion recommendation")
    if promotion_decision.recommended_registry_fingerprint != registry_fingerprint:
        raise ValueError("promotion recommendation does not identify the requested champion")
    if promotion_decision.challenger_registry_fingerprint != registry_fingerprint:
        raise ValueError("promotion decision challenger does not match the requested champion")
    if promotion_decision.required_next_gate != "V2_09_EXPLICIT_ACTIVATION_APPROVAL":
        raise ValueError("promotion decision is not at the explicit activation gate")
    if promotion_decision.promotion_allowed or promotion_decision.can_authorize_capital:
        raise ValueError("promotion evidence cannot carry capital authority")

    target = _find_entry(control_plane, registry_fingerprint)
    if target.lifecycle_state != "ELIGIBLE":
        raise ValueError("only an ELIGIBLE model may become champion")
    if target.challenger_of != promotion_decision.champion_registry_fingerprint:
        raise ValueError("challenger lineage does not match the promotion decision champion")

    current_champions = [
        entry for entry in control_plane.entries if entry.lifecycle_state == "CHAMPION"
    ]
    if current_champions:
        current_champion = current_champions[0]
        if (
            current_champion.registry_fingerprint
            != promotion_decision.champion_registry_fingerprint
        ):
            raise ValueError("promotion decision does not match the active champion")
    else:
        current_champion = None

    evidence_fingerprint = promotion_decision.decision_fingerprint
    config_fingerprint = promotion_decision.config_fingerprint
    audit_log = list(control_plane.audit_log)
    replacement_by_id: dict[str, ModelControlPlaneEntry] = {}

    if current_champion is not None:
        replacement_by_id[current_champion.registry_fingerprint] = _build_entry(
            registry_fingerprint=current_champion.registry_fingerprint,
            model_family=current_champion.model_family,
            model_version=current_champion.model_version,
            challenger_of=current_champion.challenger_of,
            lifecycle_state="ELIGIBLE",
            evidence_fingerprint=evidence_fingerprint,
            config_fingerprint=config_fingerprint,
            updated_at=occurred_at,
        )
        audit_log.append(
            _build_audit(
                registry_fingerprint=current_champion.registry_fingerprint,
                from_state="CHAMPION",
                to_state="ELIGIBLE",
                occurred_at=occurred_at,
                actor=actor,
                reason="replaced by explicitly approved challenger",
                evidence_fingerprint=evidence_fingerprint,
                config_fingerprint=config_fingerprint,
                promotion_decision_fingerprint=promotion_decision.decision_fingerprint,
                explicit_activation_approved=True,
            )
        )

    replacement_by_id[target.registry_fingerprint] = _build_entry(
        registry_fingerprint=target.registry_fingerprint,
        model_family=target.model_family,
        model_version=target.model_version,
        challenger_of=target.challenger_of,
        lifecycle_state="CHAMPION",
        evidence_fingerprint=evidence_fingerprint,
        config_fingerprint=config_fingerprint,
        updated_at=occurred_at,
    )
    audit_log.append(
        _build_audit(
            registry_fingerprint=target.registry_fingerprint,
            from_state="ELIGIBLE",
            to_state="CHAMPION",
            occurred_at=occurred_at,
            actor=actor,
            reason=reason,
            evidence_fingerprint=evidence_fingerprint,
            config_fingerprint=config_fingerprint,
            promotion_decision_fingerprint=promotion_decision.decision_fingerprint,
            explicit_activation_approved=True,
        )
    )

    entries = [
        replacement_by_id.get(entry.registry_fingerprint, entry)
        for entry in control_plane.entries
    ]
    return _build_snapshot(entries=entries, audit_log=audit_log, as_of=occurred_at)
