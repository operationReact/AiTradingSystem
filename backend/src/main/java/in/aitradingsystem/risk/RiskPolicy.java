package in.aitradingsystem.risk;

import in.aitradingsystem.config.RiskProperties;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.paper.PaperExecutionModel;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Deterministic, side-effect-free risk rules. AI output never reaches this class as an override. */
public final class RiskPolicy {
    public static final String VERSION = "risk-policy-v1";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final RiskProperties props;

    public RiskPolicy(RiskProperties props) {
        this.props = props;
    }

    public RiskDecision evaluate(RiskInput in) {
        List<RiskReason> reasons = new ArrayList<>();
        BigDecimal slip = props.slippageRate();
        BigDecimal fillPrice = PaperExecutionModel.fillPrice(in.side(), in.referencePrice(), slip);
        BigDecimal qty = BigDecimal.valueOf(in.quantity());
        BigDecimal gross = PaperExecutionModel.money(fillPrice.multiply(qty));
        BigDecimal fee = PaperExecutionModel.fee(gross, props.feeRate());
        BigDecimal slippageAmount = PaperExecutionModel.money(in.referencePrice().subtract(fillPrice).abs().multiply(qty));
        BigDecimal net = in.side() == Side.BUY ? gross.add(fee).negate() : gross.subtract(fee);
        BigDecimal riskBudget = PaperExecutionModel.money(in.startingCapital().multiply(props.riskPerTradePercent()).divide(HUNDRED));
        BigDecimal dailyLossLimit = PaperExecutionModel.money(in.startingCapital().multiply(props.maxDailyDrawdownPercent()).divide(HUNDRED));
        BigDecimal estimatedRisk = BigDecimal.ZERO.setScale(2);

        if (in.newOrdersHalted()) {
            reasons.add(RiskReason.EMERGENCY_STOP_ACTIVE);
        }
        if (in.signalGeneratedAt() != null) {
            if (in.signalGeneratedAt().isAfter(in.now())) {
                reasons.add(RiskReason.SIGNAL_TIMESTAMP_INVALID);
            } else if (Duration.between(in.signalGeneratedAt(), in.now()).toMinutes() > props.signalMaxAgeMinutes()) {
                reasons.add(RiskReason.STALE_SIGNAL);
            }
        }
        if (in.realizedPnlToday().compareTo(dailyLossLimit.negate()) <= 0) {
            reasons.add(RiskReason.DAILY_LOSS_LIMIT_REACHED);
        }

        if (in.side() == Side.BUY) {
            boolean levelsValid = in.stopLoss() != null && in.targetPrice() != null
                && in.stopLoss().compareTo(in.referencePrice()) < 0
                && in.referencePrice().compareTo(in.targetPrice()) < 0;
            if (!levelsValid) {
                reasons.add(RiskReason.INVALID_PRICE_LEVELS);
            } else {
                estimatedRisk = PaperExecutionModel.money(fillPrice.subtract(in.stopLoss()).multiply(qty).add(fee));
                if (estimatedRisk.compareTo(riskBudget) > 0) {
                    reasons.add(RiskReason.RISK_BUDGET_EXCEEDED);
                }
            }
            if (in.heldQuantity() > 0) {
                reasons.add(RiskReason.SYMBOL_ALREADY_HELD);
            } else if (in.openPositionCount() >= props.maxOpenPositions()) {
                reasons.add(RiskReason.MAX_OPEN_POSITIONS);
            }
            if (gross.add(fee).compareTo(in.availableCash()) > 0) {
                reasons.add(RiskReason.INSUFFICIENT_CASH);
            }
        } else if (in.heldQuantity() < in.quantity()) {
            reasons.add(RiskReason.INSUFFICIENT_HOLDINGS);
        }

        return new RiskDecision(reasons.isEmpty(), List.copyOf(reasons), fillPrice, gross, fee,
            slippageAmount, net, estimatedRisk, riskBudget, dailyLossLimit);
    }
}