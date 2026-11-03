from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from aitrading_intelligence.fusion_quality_multipliers import (
    FusionExpertQualityMultiplierV1,
    build_fusion_quality_multiplier,
)

NOW = datetime(2026, 1, 12, 6, 0, tzinfo=UTC)


def test_combined_multiplier_must_equal_bounded_component_product() -> None:
    valid = build_fusion_quality_multiplier(
        family="QUANT",
        evaluated_at=NOW,
    )
    tampered = valid.model_dump(mode="python")
    tampered["combined_multiplier"] = 0.42

    with pytest.raises(
        ValidationError,
        match="combined multiplier must equal bounded component product",
    ):
        FusionExpertQualityMultiplierV1.model_validate(tampered)


def test_raw_multiplier_must_equal_exact_component_product() -> None:
    valid = build_fusion_quality_multiplier(
        family="GEMINI",
        evaluated_at=NOW,
    )
    tampered = valid.model_dump(mode="python")
    tampered["raw_combined_multiplier"] = 0.75

    with pytest.raises(
        ValidationError,
        match="raw combined multiplier must equal component product",
    ):
        FusionExpertQualityMultiplierV1.model_validate(tampered)
