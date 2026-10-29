package in.aitradingsystem.scanner;

import com.fasterxml.jackson.databind.JsonNode;

/** Allowed primitive value types for persisted strategy configuration. Scripts/formulas are intentionally unsupported. */
public enum StrategyConfigValueType {
    INTEGER,
    DECIMAL,
    BOOLEAN,
    TEXT;

    public boolean accepts(JsonNode value) {
        if (value == null || value.isNull() || value.isContainerNode()) return false;
        return switch (this) {
            case INTEGER -> value.isIntegralNumber();
            case DECIMAL -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
            case TEXT -> value.isTextual();
        };
    }
}
