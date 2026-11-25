package in.aitradingsystem.risk;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Client-owned trade setup only. Portfolio state and enforced policy are always loaded server-side. */
public record PositionSizeRequest(
        @Min(1) long instrumentId,
        @NotBlank @Size(max = 20) String exchange,
        @NotBlank @Size(max = 80) String symbol,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal entryPrice,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal stopLoss,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal targetPrice,
        @NotBlank @Size(max = 120) String strategy,
        @NotNull TradingStyle tradingStyle) {
}
