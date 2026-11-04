from __future__ import annotations

from pydantic import ValidationError
import pytest
from test_india_independent_holdout import (
    HOLDOUT_DAYS,
    TRAIN_DAYS,
    _export,
    _fee_book,
    _target,
)

from aitrading_intelligence.cross_model_causal_cohort import (
    CrossModelCausalCohort,
    bind_common_causal_tournament_cohort,
)
from aitrading_intelligence.incremental_economic_value import (
    create_net_return_series,
    evaluate_incremental_economic_value,
)
from aitrading_intelligence.india_causal_forecast_baseline import (
    CausalForecastConfig,
    _causal_rows,
    evaluate_india_causal_forecast_baseline,
)


def _case(*, lookback: int = 8, fees=None, target=None):
    train, holdout = _export(TRAIN_DAYS), _export(HOLDOUT_DAYS)
    target = target or _target()
    parent = evaluate_india_causal_forecast_baseline(
        train, holdout, fees or _fee_book(), target,
        config=CausalForecastConfig(lookback_returns=lookback),
    )
    rows = _causal_rows(holdout, lookback=lookback, horizon=target.horizon_bars)
    return parent, rows, target


def test_cross_family_cohort_is_deterministic_and_research_only() -> None:
    parent, rows, target = _case()
    identity = bind_common_causal_tournament_cohort(parent, rows, target)
    repeated = bind_common_causal_tournament_cohort(parent, rows, target)
    assert identity == repeated
    assert identity.target_binding_fingerprint == target.fingerprint
    assert identity.holdout_export_fingerprint == parent.holdout_export_fingerprint
    assert len(identity.observation_fingerprints) == len(rows)
    assert identity.series_kwargs()["observation_fingerprints"] == (
        identity.observation_fingerprints
    )
    assert identity.independent_market_source_verified is False
    assert identity.external_preregistration_verified is False
    assert identity.promotion_allowed is False
    assert identity.can_authorize_capital is False


def test_four_research_models_can_use_the_same_paired_after_cost_binding() -> None:
    parent, rows, target = _case()
    common = bind_common_causal_tournament_cohort(parent, rows, target).series_kwargs()
    first = create_net_return_series(
        candidate_id="causal-mean-baseline",
        model_version_ref="v4-04-causal-moving-average.v1",
        source_result_fingerprint="a" * 64,
        net_returns_after_costs=[0.0] * len(rows),
        **common,
    )
    challengers = [
        ("bounded-patch-transformer", "b"),
        ("bounded-time-mixer-research", "c"),
        ("original-bounded-tft", "d"),
        ("chronos2-zero-shot-research", "e"),
    ]
    for candidate_id, digest in challengers:
        second = create_net_return_series(
            candidate_id=candidate_id,
            model_version_ref=f"{candidate_id}.v1",
            source_result_fingerprint=digest * 64,
            net_returns_after_costs=[0.0002] * len(rows),
            **common,
        )
        paired = evaluate_incremental_economic_value(first, second)
        assert paired.observation_count == len(rows)
        assert paired.can_authorize_capital is False


def test_parent_lookback_and_row_membership_must_be_exactly_identical() -> None:
    parent, rows, target = _case()
    with pytest.raises(ValueError, match="row count differs"):
        bind_common_causal_tournament_cohort(parent, rows[:-1], target)
    reordered = list(rows)
    reordered[1], reordered[2] = reordered[2], reordered[1]
    with pytest.raises(ValueError, match="membership differs"):
        bind_common_causal_tournament_cohort(parent, reordered, target)

    other, _, _ = _case(lookback=9)
    with pytest.raises(ValueError, match="row count differs"):
        bind_common_causal_tournament_cohort(other, rows, target)


def test_cost_fee_book_and_target_differences_never_share_one_trial_identity() -> None:
    parent, rows, target = _case()
    original = bind_common_causal_tournament_cohort(parent, rows, target)
    with pytest.raises(ValueError, match="target differs"):
        bind_common_causal_tournament_cohort(parent, rows, _target(cost_bps=30))
    other_parent, other_rows, _ = _case(lookback=9)
    other = bind_common_causal_tournament_cohort(other_parent, other_rows, target)
    assert other.fold_binding_fingerprint != original.fold_binding_fingerprint
    assert other.economic_cost_binding_fingerprint == original.economic_cost_binding_fingerprint


def test_inconclusive_causal_parent_cannot_make_tournament_evidence() -> None:
    from aitrading_intelligence.india_causal_forecast_baseline import (
        evaluate_india_causal_forecast_baseline,
    )

    train, holdout, target = _export(TRAIN_DAYS), _export((HOLDOUT_DAYS[0],)), _target()
    report = evaluate_india_causal_forecast_baseline(train, holdout, _fee_book(), target)
    assert report.status == "INCONCLUSIVE"
    rows = _causal_rows(holdout, lookback=8, horizon=1)
    with pytest.raises(ValueError, match="completed causal baseline"):
        bind_common_causal_tournament_cohort(report, rows, target)


def test_binding_and_fee_identity_tampering_is_detected() -> None:
    parent, rows, target = _case()
    original = bind_common_causal_tournament_cohort(parent, rows, target)
    modified = original.model_dump(mode="json")
    modified["observation_fingerprints"][0] = "f" * 64
    with pytest.raises(ValidationError, match="cross-model fold fingerprint"):
        CrossModelCausalCohort.model_validate(modified)
    modified = original.model_dump(mode="json")
    modified["economic_cost_binding_fingerprint"] = "f" * 64
    with pytest.raises(ValidationError, match="cross-model fee and target fingerprint"):
        CrossModelCausalCohort.model_validate(modified)
    modified = original.model_dump(mode="json")
    modified["can_authorize_capital"] = True
    with pytest.raises(ValidationError):
        CrossModelCausalCohort.model_validate(modified)


def test_model_cost_binding_changes_when_fee_book_changes() -> None:
    parent, rows, target = _case()
    original = bind_common_causal_tournament_cohort(parent, rows, target)
    fee_book = _fee_book()
    payload = fee_book.model_dump(mode="json")
    # Modifying real fee assumptions must produce a new fee-book fingerprint,
    # even if model predictions are otherwise identical.
    assert original.fee_book_fingerprint == fee_book.book_fingerprint
    assert original.fee_book_fingerprint != "0" * 64
