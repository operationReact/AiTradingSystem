package in.aitradingsystem.paper;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import in.aitradingsystem.paper.AutomatedPaperPositionRepository.MonitoredPosition;
import in.aitradingsystem.risk.RiskPolicyConfigRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Supervises persisted PAPER positions in PAPER + AUTOMATED mode.
 *
 * <p>Protective exits are submitted only through {@link PaperTradingService}; this service never calls the
 * execution engine directly, so the existing Risk Engine, execution policy, approval policy, readiness checks,
 * fill/accounting and audit path remain authoritative.
 */
@Service
public class AutomatedPaperPositionMonitorService {
    private final TradingExecutionAuthority authority;
    private final AutomatedPaperPositionRepository positions;
    private final PaperTradingService trading;
    private final RiskPolicyConfigRepository riskPolicies;
    private final AuditService audit;
    private final Clock clock;

    @Autowired
    public AutomatedPaperPositionMonitorService(
            TradingExecutionAuthority authority,
            AutomatedPaperPositionRepository positions,
            PaperTradingService trading,
            RiskPolicyConfigRepository riskPolicies,
            AuditService audit) {
        this(authority, positions, trading, riskPolicies, audit, Clock.systemUTC());
    }

    AutomatedPaperPositionMonitorService(
            TradingExecutionAuthority authority,
            AutomatedPaperPositionRepository positions,
            PaperTradingService trading,
            RiskPolicyConfigRepository riskPolicies,
            AuditService audit,
            Clock clock) {
        this.authority = authority;
        this.positions = positions;
        this.trading = trading;
        this.riskPolicies = riskPolicies;
        this.audit = audit;
        this.clock = clock;
    }

    public MonitorRun runOnce() {
        return runOnce(clock.instant());
    }

    MonitorRun runOnce(Instant now) {
        if (now == null) throw new IllegalArgumentException("now is required");
        if (!authority.current().automatedPaper()) {
            return MonitorRun.disabled(now);
        }
        if (trading.isHalted()) {
            return MonitorRun.halted(now);
        }

        long maxMarkAgeSeconds = riskPolicies.requireActive().marketStateMaxAgeSeconds();
        List<MonitoredPosition> open = positions.openPositions(now);
        int triggered = 0;
        int submitted = 0;
        int replayed = 0;
        int rejected = 0;
        int stale = 0;
        List<ExitAttempt> attempts = new ArrayList<>();

        for (MonitoredPosition persisted : open) {
            if (stale(persisted, now, maxMarkAgeSeconds)) {
                stale++;
                attempts.add(new ExitAttempt(persisted.symbol(), null, "STALE_MARK", null));
                continue;
            }

            MonitoredPosition position = persisted;
            if (persisted.trailingStopPercent() != null) {
                try {
                    position = refreshTrailingState(persisted, now);
                    if (position == null) {
                        rejected++;
                        attempts.add(new ExitAttempt(persisted.symbol(), null, "TRAILING_STATE_CONCURRENT", null));
                        continue;
                    }
                } catch (RuntimeException ex) {
                    rejected++;
                    attempts.add(new ExitAttempt(persisted.symbol(), null, "TRAILING_STATE_INVALID", null));
                    audit.record("AUTOMATED_PAPER_TRAILING_STATE_FAILED", "PAPER_POSITION", positionIdentity(persisted),
                        Map.of("symbol", persisted.symbol(), "productType", persisted.productType().name(),
                            "failure", ex.getClass().getSimpleName()));
                    continue;
                }
            }

            ExitReason reason = trigger(position);
            if (reason == null) continue;
            triggered++;

            String key = idempotencyKey(position, reason);
            OrderCommand command = new OrderCommand(
                position.symbol(),
                Side.SELL,
                position.quantity(),
                position.markPrice(),
                null,
                null,
                position.markAt(),
                position.productType(),
                null,
                "AUTOMATED",
                reason.name());

            try {
                PaperViews.OrderResult result = trading.placeOrder(command, key);
                String status = result.order() == null ? "SUBMITTED" : result.order().status();
                if (result.replayed()) {
                    replayed++;
                } else if ("REJECTED".equals(status)) {
                    rejected++;
                } else {
                    submitted++;
                }
                attempts.add(new ExitAttempt(position.symbol(), reason.name(), status, key));
                audit.record("AUTOMATED_PAPER_EXIT", "PAPER_POSITION", positionIdentity(position), Map.of(
                    "symbol", position.symbol(),
                    "productType", position.productType().name(),
                    "reason", reason.name(),
                    "markPrice", position.markPrice().toPlainString(),
                    "quantity", position.quantity(),
                    "status", status,
                    "idempotencyKey", key));
            } catch (RuntimeException ex) {
                rejected++;
                attempts.add(new ExitAttempt(position.symbol(), reason.name(), "FAILED", key));
                audit.record("AUTOMATED_PAPER_EXIT_FAILED", "PAPER_POSITION", positionIdentity(position), Map.of(
                    "symbol", position.symbol(),
                    "productType", position.productType().name(),
                    "reason", reason.name(),
                    "failure", ex.getClass().getSimpleName(),
                    "idempotencyKey", key));
            }
        }

        return new MonitorRun(true, false, now, open.size(), triggered, submitted, replayed, rejected, stale,
            List.copyOf(attempts));
    }

    private MonitoredPosition refreshTrailingState(MonitoredPosition position, Instant now) {
        BigDecimal currentHigh = position.highWaterMark();
        if (currentHigh == null || position.highWaterAt() == null || position.effectiveProtectiveStop() == null) {
            throw new IllegalStateException("Persisted trailing state is incomplete");
        }
        BigDecimal nextHigh = currentHigh.max(position.markPrice());
        BigDecimal nextStop = TrailingProtectionPolicy.effectiveStop(
            position.stopLoss(), nextHigh, position.trailingStopPercent());
        if (position.effectiveProtectiveStop().compareTo(nextStop) > 0) {
            nextStop = position.effectiveProtectiveStop();
        }
        if (nextHigh.compareTo(currentHigh) <= 0) {
            return position.withTrailingState(currentHigh, position.highWaterAt(), nextStop, position.positionUpdatedAt());
        }
        if (!positions.advanceTrailingState(position, nextHigh, position.markAt(), nextStop, now)) {
            return null;
        }
        return position.withTrailingState(nextHigh, position.markAt(), nextStop, now);
    }

    static ExitReason trigger(MonitoredPosition position) {
        if (position.stopLoss() != null && position.markPrice().compareTo(position.stopLoss()) <= 0) {
            return ExitReason.STOP_LOSS;
        }
        if (position.targetPrice() != null && position.markPrice().compareTo(position.targetPrice()) >= 0) {
            return ExitReason.TARGET;
        }
        if (position.trailingStopPercent() != null && position.effectiveProtectiveStop() != null
                && position.markPrice().compareTo(position.effectiveProtectiveStop()) <= 0) {
            return ExitReason.TRAILING_STOP;
        }
        return null;
    }

    static boolean stale(MonitoredPosition position, Instant now, long maxMarkAgeSeconds) {
        if (position.markPrice() == null || position.markAt() == null || position.markAt().isAfter(now)) return true;
        return Duration.between(position.markAt(), now).getSeconds() > maxMarkAgeSeconds;
    }

    static String idempotencyKey(MonitoredPosition position, ExitReason reason) {
        long protectionVersion = reason == ExitReason.TRAILING_STOP && position.highWaterAt() != null
            ? position.highWaterAt().toEpochMilli()
            : position.positionUpdatedAt().toEpochMilli();
        return "auto-paper-exit:"
            + reason.name().toLowerCase(java.util.Locale.ROOT)
            + ":" + positionIdentity(position)
            + ":" + protectionVersion
            + ":" + position.markAt().toEpochMilli();
    }

    private static String positionIdentity(MonitoredPosition position) {
        String instrument = position.instrumentId() == null ? position.symbol() : position.instrumentId().toString();
        return instrument + ":" + position.productType().name();
    }

    enum ExitReason { STOP_LOSS, TARGET, TRAILING_STOP }

    public record ExitAttempt(String symbol, String reason, String status, String idempotencyKey) {}

    public record MonitorRun(
        boolean automationEnabled,
        boolean emergencyStopped,
        Instant checkedAt,
        int positionsChecked,
        int triggered,
        int submitted,
        int replayed,
        int rejectedOrFailed,
        int staleSkipped,
        List<ExitAttempt> attempts
    ) {
        static MonitorRun disabled(Instant at) {
            return new MonitorRun(false, false, at, 0, 0, 0, 0, 0, 0, List.of());
        }

        static MonitorRun halted(Instant at) {
            return new MonitorRun(true, true, at, 0, 0, 0, 0, 0, 0, List.of());
        }
    }
}
