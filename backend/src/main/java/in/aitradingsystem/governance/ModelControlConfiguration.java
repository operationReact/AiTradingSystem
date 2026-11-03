package in.aitradingsystem.governance;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Bounded model-decision settings accepted by the Java control plane.
 * Weights and probability-style thresholds are normalized to the closed interval [0, 1].
 */
public record ModelControlConfiguration(
    @NotBlank String modelVersionId,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal ensembleWeight,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal minimumCalibratedProbability,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal uncertaintyThreshold,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal abstentionThreshold
) {}
