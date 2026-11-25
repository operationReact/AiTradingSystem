package in.aitradingsystem.governance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BoundedModelConfigurationValidatorTest {
    private final BoundedModelConfigurationValidator validator = new BoundedModelConfigurationValidator(
        Validation.buildDefaultValidatorFactory().getValidator()
    );

    @Test
    void acceptsClosedIntervalBoundaries() {
        ModelControlConfiguration configuration = new ModelControlConfiguration(
            "catboost-v1",
            BigDecimal.ZERO,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ONE
        );

        assertDoesNotThrow(() -> validator.validate(configuration));
    }

    @Test
    void rejectsEnsembleWeightAboveOne() {
        ModelControlConfiguration configuration = new ModelControlConfiguration(
            "catboost-v1",
            new BigDecimal("1.01"),
            new BigDecimal("0.60"),
            new BigDecimal("0.20"),
            new BigDecimal("0.30")
        );

        assertThrows(ConstraintViolationException.class, () -> validator.validate(configuration));
    }

    @Test
    void rejectsNegativeProbabilityThreshold() {
        ModelControlConfiguration configuration = new ModelControlConfiguration(
            "catboost-v1",
            new BigDecimal("0.50"),
            new BigDecimal("-0.01"),
            new BigDecimal("0.20"),
            new BigDecimal("0.30")
        );

        assertThrows(ConstraintViolationException.class, () -> validator.validate(configuration));
    }

    @Test
    void rejectsMissingModelVersion() {
        ModelControlConfiguration configuration = new ModelControlConfiguration(
            " ",
            new BigDecimal("0.50"),
            new BigDecimal("0.60"),
            new BigDecimal("0.20"),
            new BigDecimal("0.30")
        );

        assertThrows(ConstraintViolationException.class, () -> validator.validate(configuration));
    }
}
