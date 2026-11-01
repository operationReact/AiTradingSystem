package in.aitradingsystem.paper;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import in.aitradingsystem.paper.AutomatedPaperPositionRepository.MonitoredPosition;
import in.aitradingsystem.risk.RiskPolicyConfigRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final PaperPositionRepository positionState;
    private final PaperTradingService trading;
    private final RiskPolicyConfigRepository riskPolicies;
    private final AuditService audit;

    public AutomatedPaperPositionMonitorService(
            TradingExecutionAuthority authority,
            AutomatedPaperPositionRepository positions,
            PaperPositionRepository positionState,
            PaperTradingService trading,
            RiskPolicyConfigRepository riskPolicies,
            AuditService audit) {
        this.authority = authority;
        this.positions = positions;
        this.positionState = positionState;
        this.trading = trading;
        this.riskPolicies = riskPolicies;
        this.audit = audit;
    }

    public MonitorRun runOnce() {
        return runOnce(Instant.now());
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

        for (MonitoredPosition snapshot : open) {
            if (stale(snapshot, now, maxMarkAgeSeconds)) {
                stale++;
                attempts.add(new ExitAttempt(snapshot.symbol(), null, "STALE_MARK", null));
                continue;
            }

            MonitoredPosition position = snapshot;
            if (position.trailingPercent() != null) {
                if (!validTrailingState(position)) {
                    rejected++;
                    attempts.add(new ExitAttempt(position.symbol(), null, "INVALID_TRAILING_STATE", null));
                    continue;
                }
                if (position.markPrice().compareTo(position.highWaterMark()) > 0) {
                    BigDecimal newHigh = price(position.markPrice());
                    BigDecimal newEffectiveStop = PositionAccountingService.effectiveStop(
                        position.stopLoss(), newHigh, position.trailingPercent());
                    boolean advanced = positionState.advanceTrailingState(
                        position.symbol(), position.productType(), position.trailingVersion(),
                        newHigh, position.markAt(), newEffectiveStop);
                    if (!advanced) {
                        rejected++;
                        attempts.add(new ExitAttempt(position.symbol(), null, "TRAILING_STATE_CONFLICT", null));
                        continue;
                    }
                    position = withTrailingState(position, newHigh, position.markAt(), newEffectiveStop,
                        position.trailingVersion() + 1);
                    audit.record("PAPER_TRAILING_STATE_ADVANCED", "PAPER_POSITION", positionIdentity(position), Map.of(
                        "symbol", position.symbol(),
                        "productType", position.productType().name(),
                        "highWaterMark", newHigh.toPlainString(),
                        "effectiveProtectiveStop", newEffectiveStop.toPlainString(),
                        "trailingVersion", position.trailingVersion()));
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
                position.productType());

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

    static ExitReason trigger(MonitoredPosition position) {
        BigDecimal protectiveStop = position.trailingPercent() == null
            ? position.stopLoss() : position.effectiveProtectiveStop();
        if (protectiveStop != null && position.markPrice().compareTo(protectiveStop) <= 0) {
            boolean trailingTightened = position.trailingPercent() != null
                && position.stopLoss() != null
                && protectiveStop.compareTo(position.stopLoss()) > 0;
            return trailingTightened ? ExitReason.TRAILING_STOP : ExitReason.STOP_LOSS;
        }
        if (position.targetPrice() != null && position.markPrice().compareTo(position.targetPrice()) >= 0) {
            return ExitReason.TARGET;
        }
        return null;
    }

    static boolean stale(MonitoredPosition position, Instant now, long maxMarkAgeSeconds) {
        if (position.markPrice() == null || position.markAt() == null || position.markAt().isAfter(now)) return true;
        return Duration.between(position.markAt(), now).getSeconds() > maxMarkAgeSeconds;
    }

    static String idempotencyKey(MonitoredPosition position, ExitReason reason) {
        return "auto-paper-exit:"
            + reason.name().toLowerCase(java.util.Locale.ROOT)
            + ":" + positionIdentity(position)
            + ":" + position.positionUpdatedAt().toEpochMilli()
            + ":tv" + position.trailingVersion()
            + ":" + position.markAt().toEpochMilli();
    }

    private static boolean validTrailingState(MonitoredPosition position) {
        return position.stopLoss() != null
            && position.trailingPercent() != null
            && position.trailingPercent().signum() > 0
            && position.trailingPercent().compareTo(new BigDecimal("100")) < 0
            && position.highWaterMark() != null
            && position.highWaterAt() != null
            && position.effectiveProtectiveStop() != null;
    }

    private static MonitoredPosition withTrailingState(MonitoredPosition position, BigDecimal highWater,
                                                        Instant highWaterAt, BigDecimal effectiveStop,
                                                        long trailingVersion) {
        return new MonitoredPosition(
            position.instrumentId(), position.exchange(), position.symbol(), position.productType(), position.quantity(),
            position.stopLoss(), position.targetPrice(), position.trailingPercent(), highWater, highWaterAt,
            effectiveStop, trailingVersion, position.markPrice(), position.markAt(), position.positionUpdatedAt());
    }

    private static BigDecimal price(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static String positionIdentity(MonitoredPosition position) {
        String instrument = position.instrumentId() == null ? position.symbol() : position.instrumentId().toString();
        return instrument + ":" + position.productType().name();
    }

    enum ExitReason { STOP_LOSS, TRAILING_STOP, TARGET }

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
