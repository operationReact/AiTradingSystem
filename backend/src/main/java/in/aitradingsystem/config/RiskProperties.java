package in.aitradingsystem.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.risk")
public record RiskProperties(
    @NotNull @DecimalMin("10000") @DecimalMax("100000000") BigDecimal defaultCapital,
    @NotNull @DecimalMin("0.01") @DecimalMax("5") BigDecimal riskPerTradePercent,
    @NotNull @DecimalMin("0.01") @DecimalMax("10") BigDecimal maxDailyDrawdownPercent,
    @Min(1) @Max(50) int maxOpenPositions,
    @NotNull @DecimalMin("0") @DecimalMax("0.01") BigDecimal feeRate,
    @NotNull @DecimalMin("0") @DecimalMax("0.05") BigDecimal slippageRate,
    @Min(1) @Max(1440) int signalMaxAgeMinutes
) {}