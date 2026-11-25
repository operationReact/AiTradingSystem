package in.aitradingsystem.risk;

import in.aitradingsystem.config.RiskProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RiskPolicyBootstrap implements ApplicationRunner {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MINIMUM_REWARD_RISK = BigDecimal.ONE;
    private static final BigDecimal UNBOUNDED_NOTIONAL = new BigDecimal("999999999999999.99");
    private static final int MAX_TRADES_PER_DAY = 1000;
    private static final int MAX_MARKET_STATE_AGE_SECONDS = 3600;

    private final RiskPolicyConfigRepository repository;
    private final RiskProperties properties;

    public RiskPolicyBootstrap(RiskPolicyConfigRepository repository, RiskProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.existsAny()) return;
        repository.insert(snapshot(), "Bootstrap risk policy", true, "SYSTEM_BOOTSTRAP");
    }

    private RiskPolicySnapshot snapshot() {
        String canonical = String.join("|",
                properties.riskPerTradePercent().toPlainString(),
                properties.maxDailyDrawdownPercent().toPlainString(),
                Integer.toString(properties.maxOpenPositions()),
                properties.feeRate().toPlainString(),
                properties.slippageRate().toPlainString(),
                Integer.toString(properties.signalMaxAgeMinutes()));
        UUID id = UUID.nameUUIDFromBytes(("risk-policy-bootstrap-v1|" + canonical).getBytes(StandardCharsets.UTF_8));
        return new RiskPolicySnapshot(
                id,
                1,
                properties.riskPerTradePercent(),
                properties.maxDailyDrawdownPercent(),
                properties.maxOpenPositions(),
                MINIMUM_REWARD_RISK,
                UNBOUNDED_NOTIONAL,
                HUNDRED,
                HUNDRED,
                MAX_TRADES_PER_DAY,
                properties.signalMaxAgeMinutes(),
                MAX_MARKET_STATE_AGE_SECONDS,
                false,
                true,
                false,
                properties.feeRate(),
                properties.slippageRate(),
                "legacy-risk-v1-bootstrap");
    }
}
