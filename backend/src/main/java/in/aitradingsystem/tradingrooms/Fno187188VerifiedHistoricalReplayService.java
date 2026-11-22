package in.aitradingsystem.tradingrooms;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Point-in-time original source historical research for two OWNED equity options.
 *
 * <p>Reuses the unchanged FnoOwnedEquityOptionsResearchService, not synthetic
 * holdings or alternative trading rules. Its PostgreSQL V110 source verifies
 * exchange calendars, original NSE_EQ closes, immutable as-of original quotes,
 * and broker-reconciled SETTLED one-full-lot owned equity, lien/pledge-free,
 * with original source-use rights for stock/option/positions/fee assumptions.
 * Everything is read-only; a present broker account NOW cannot prove
 * historical ownership and no market E2E, PAPER or LIVE is approved.
 */
@Service
public class Fno187188VerifiedHistoricalReplayService {
    public static final String CONTRACT = "minttick.fno187188.original-owned-equity-replay.v1";
    private static final String PUT = FnoProtectivePutV1.STRATEGY_ID;
    private static final String CALL = FnoCoveredCallV1.STRATEGY_ID;
    private final FnoOwnedEquityOptionsResearchService original;
    private final Clock clock;

    @Autowired
    public Fno187188VerifiedHistoricalReplayService(
            FnoOwnedEquityOptionsResearchService original) {
        this(original, Clock.systemUTC());
    }

    Fno187188VerifiedHistoricalReplayService(
            FnoOwnedEquityOptionsResearchService original, Clock clock) {
        this.original = Objects.requireNonNull(original);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Sanitized counts and research-only status; no account, prices, source or contract keys. */
    public record Evidence(
        String strategyId, Instant asOf, String state, String reasonCode,
        int verifiedOriginalEquitySessions, int settledBrokerOwnedContracts,
        boolean historicalMarketE2E, boolean paperLedgerE2E,
        boolean liveBrokerE2E, boolean brokerOrdersPlaced) {}

    public Evidence inspect(String id, Instant asOf) {
        if (!PUT.equals(id) && !CALL.equals(id))
            return blocked(id, asOf, "OWNED_OPTION_HISTORICAL_STRATEGY_UNSUPPORTED");
        if (asOf == null || asOf.isAfter(clock.instant())
                || asOf.isBefore(clock.instant().minus(Duration.ofDays(366))))
            return blocked(id, asOf, "OWNED_OPTION_HISTORICAL_SCOPE_INVALID");
        final List<FnoFuturesResearchService.StrategyStatus> statuses;
        try {
            statuses = original.evaluateAll(asOf);
        } catch (RuntimeException ignored) {
            // Never expose token, account scope, holdings quantity or SQL errors.
            return blocked(id, asOf, "ORIGINAL_BROKER_OWNED_SOURCE_UNAVAILABLE");
        }
        if (statuses == null || statuses.size() != 2)
            return blocked(id, asOf, "ORIGINAL_OWNED_OPTION_PAIR_SOURCE_INCOMPLETE");
        var selected = statuses.stream().filter(x -> x != null
            && id.equals(x.strategyId())).findFirst().orElse(null);
        if (selected == null || !asOf.equals(selected.evaluatedAt()))
            return blocked(id, asOf, "ORIGINAL_OWNED_OPTION_EVALUATOR_UNAVAILABLE");
        var expected = PUT.equals(id)
            ? "SETTLED_EQUITY_PROTECTIVE_PUT_RESEARCH_NO_ORDER"
            : "SETTLED_EQUITY_COVERED_CALL_RESEARCH_NO_ORDER";
        boolean verified = expected.equals(selected.inputState())
            && ("RESEARCH_CANDIDATE".equals(selected.status())
                || "NO_SIGNAL".equals(selected.status()))
            && selected.historySessions() >= 35 && selected.historySessions() <= 90
            && selected.contractsResolved() == 1 && selected.freshQuotes() == 2
            && selected.reasonCode() != null
            && selected.reasonCode().matches("[A-Z0-9_]{4,128}");
        if (!verified)
            return blocked(id, asOf,
                selected.reasonCode() != null
                    && selected.reasonCode().matches("[A-Z0-9_]{4,128}")
                        ? selected.reasonCode()
                        : "ORIGINAL_SETTLED_BROKER_CUSTODY_OR_SOURCE_NOT_VERIFIED");
        return new Evidence(id, asOf, "ORIGINAL_OWNED_STOCK_EVALUATOR_RESEARCH_ONLY",
            selected.reasonCode(), selected.historySessions(), 1,
            false, false, false, false);
    }

    private static Evidence blocked(String id, Instant asOf, String reason) {
        return new Evidence(id, asOf, "BLOCKED_NOT_HISTORICAL_E2E", reason,
            0, 0, false, false, false, false);
    }
}
