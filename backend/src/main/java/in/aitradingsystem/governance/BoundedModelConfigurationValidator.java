package in.aitradingsystem.governance;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Server-side validation boundary for model-control mutations. UI validation is advisory only;
 * any future Admin mutation endpoint must pass proposed settings through this validator.
 */
@Component
public class BoundedModelConfigurationValidator {
    private final Validator validator;

    public BoundedModelConfigurationValidator(Validator validator) {
        this.validator = validator;
    }

    public ModelControlConfiguration validate(ModelControlConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        var violations = validator.validate(configuration);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return configuration;
    }
}
