from __future__ import annotations

from collections.abc import Callable, Sequence
from datetime import datetime
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field

from .data_quality import (
    SourceHealthReport,
    SourceHealthRule,
    SourceHealthSample,
    build_source_health_report,
)
from .india_vix_source import IndiaVixHistory
from .nifty50_history_source import Nifty50History
from .nifty_constituent_source import NiftyConstituentSnapshot, select_nifty50_snapshot
from .nifty_sector_history_source import SECTOR_INDEX_CODES, SectorIndexHistory

OFFICIAL_SOURCE_HEALTH_POLICY_VERSION = "official-source-health-policy.v1"


class OfficialSourceHealthPolicy(BaseModel):
    """Freshness policy for the official India context feeds used by V3 research."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    daily_max_age_seconds: Annotated[int, Field(ge=86_400, le=604_800)] = 432_000
    constituent_max_age_seconds: Annotated[int, Field(ge=86_400, le=2_592_000)] = 1_209_600


def _latest_history_sample(
    *,
    family: str,
    source: str,
    as_of: datetime,
    rows: Sequence[object],
    causal_fingerprint: Callable[[], str],
) -> SourceHealthSample | None:
    causal = [row for row in rows if row.available_at <= as_of]
    if not causal:
        return None
    latest = max(causal, key=lambda row: (row.available_at, row.trading_date))
    return SourceHealthSample(
        family=family,
        source=source,
        source_id=causal_fingerprint(),
        event_at=latest.available_at,
        available_at=latest.available_at,
    )


def _constituent_sample(
    snapshots: Sequence[NiftyConstituentSnapshot],
    *,
    as_of: datetime,
) -> SourceHealthSample | None:
    try:
        selected = select_nifty50_snapshot(list(snapshots), as_of=as_of)
    except ValueError as exc:
        if "no NIFTY 50 constituent snapshot was observed by as_of" not in str(exc):
            raise
        return None
    return SourceHealthSample(
        family="NIFTY_CONSTITUENTS",
        source="NIFTY_INDICES_CONSTITUENTS",
        source_id=selected.snapshot_fingerprint,
        event_at=selected.observed_at,
        available_at=selected.observed_at,
    )


def build_official_source_health_report(
    *,
    as_of: datetime,
    constituent_snapshots: Sequence[NiftyConstituentSnapshot],
    nifty50_history: Nifty50History | None,
    india_vix_history: IndiaVixHistory | None,
    sector_histories: Sequence[SectorIndexHistory],
    required_sector_indices: Sequence[str],
    policy: OfficialSourceHealthPolicy | None = None,
) -> SourceHealthReport:
    """Evaluate the official NIFTY/VIX/sector feeds using the shared fail-closed health gate."""

    if as_of.tzinfo is None or as_of.utcoffset() is None:
        raise ValueError("as_of must be timezone-aware")
    policy = policy or OfficialSourceHealthPolicy()

    normalized_required = [
        item.strip().upper().replace(" ", "_")
        for item in required_sector_indices
    ]
    if not normalized_required:
        raise ValueError("at least one required sector index is required")
    if len(set(normalized_required)) != len(normalized_required):
        raise ValueError("required sector indices must be unique")
    unsupported = sorted(set(normalized_required).difference(SECTOR_INDEX_CODES))
    if unsupported:
        raise ValueError("unsupported required sector index: " + ",".join(unsupported))

    sector_by_code = {history.index_code: history for history in sector_histories}
    if len(sector_by_code) != len(sector_histories):
        raise ValueError("sector histories must contain unique index codes")
    unexpected = sorted(set(sector_by_code).difference(normalized_required))
    if unexpected:
        raise ValueError("sector history has no required health rule: " + ",".join(unexpected))

    rules = [
        SourceHealthRule(
            family="NIFTY_CONSTITUENTS",
            max_age_seconds=policy.constituent_max_age_seconds,
        ),
        SourceHealthRule(
            family="NIFTY50_HISTORY",
            max_age_seconds=policy.daily_max_age_seconds,
        ),
        SourceHealthRule(
            family="INDIA_VIX_HISTORY",
            max_age_seconds=policy.daily_max_age_seconds,
        ),
        *[
            SourceHealthRule(
                family=f"SECTOR_{index_code}",
                max_age_seconds=policy.daily_max_age_seconds,
            )
            for index_code in normalized_required
        ],
    ]

    samples: list[SourceHealthSample] = []
    constituent = _constituent_sample(constituent_snapshots, as_of=as_of)
    if constituent is not None:
        samples.append(constituent)

    if nifty50_history is not None:
        sample = _latest_history_sample(
            family="NIFTY50_HISTORY",
            source="NSE_INDIA_HISTORICAL_INDEX",
            as_of=as_of,
            rows=nifty50_history.rows,
            causal_fingerprint=lambda: nifty50_history.causal_source_fingerprint(
                as_of=as_of
            ),
        )
        if sample is not None:
            samples.append(sample)

    if india_vix_history is not None:
        sample = _latest_history_sample(
            family="INDIA_VIX_HISTORY",
            source="NSE_INDIA_HISTORICAL_VIX",
            as_of=as_of,
            rows=india_vix_history.rows,
            causal_fingerprint=lambda: india_vix_history.causal_source_fingerprint(
                as_of=as_of
            ),
        )
        if sample is not None:
            samples.append(sample)

    for index_code in normalized_required:
        history = sector_by_code.get(index_code)
        if history is None:
            continue
        sample = _latest_history_sample(
            family=f"SECTOR_{index_code}",
            source="NSE_INDIA_HISTORICAL_SECTOR_INDEX",
            as_of=as_of,
            rows=history.rows,
            causal_fingerprint=lambda history=history: history.causal_source_fingerprint(
                as_of=as_of
            ),
        )
        if sample is not None:
            samples.append(sample)

    return build_source_health_report(as_of=as_of, rules=rules, samples=samples)
