package in.aitradingsystem.paper;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.paper.PaperAccountRepository.AccountSnapshot;
import in.aitradingsystem.paper.PaperFillRepository.FillWrite;
import in.aitradingsystem.paper.PositionAccountingService.AccountingResult;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskDecisionV2;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Applies one deterministic PAPER execution to account, position, fill and order-summary persistence. */
@Service
public class PaperExecutionEngine {
    static final String EXECUTION_MODEL_VERSION = "PAPER_EXECUTION_V1";
    static final String LIQUIDITY_ASSUMPTION = "FULL_LIQUIDITY";

    private final PaperAccountRepository accounts;
    private final PositionAccountingService positionAccounting;
    private final PaperFillRepository fills;
    private final PaperOrderRepository orders;

    public PaperExecutionEngine(PaperAccountRepository accounts, PositionAccountingService positionAccounting,
                                PaperFillRepository fills, PaperOrderRepository orders) {
        this.accounts = accounts;
        this.positionAccounting = positionAccounting;
        this.fills = fills;
        this.orders = orders;
    }

    public ExecutionResult execute(UUID orderId, OrderCommand command, RiskContext context,
                                   RiskDecisionV2 decision, AccountSnapshot account, Instant now) {
        Long instrumentId = "PAPER".equalsIgnoreCase(context.exchange()) ? null : context.instrumentId();
        BigDecimal cashAfter;
        BigDecimal netAmount;
        AccountingResult accounting;

        if (command.side() == Side.BUY) {
            BigDecimal fillCostBasis = money(decision.grossNotional().add(decision.estimatedFee()));
            accounting = positionAccounting.applyBuy(instrumentId, context.exchange(), command.symbol(),
                command.productType(), command.quantity(), fillCostBasis, command.stopLoss(), command.target(),
                decision.effectiveFillPrice(), command.trailingStopPercent(), now);
            cashAfter = money(account.cash().subtract(fillCostBasis));
            netAmount = fillCostBasis.negate();
        } else {
            netAmount = money(decision.grossNotional().subtract(decision.estimatedFee()));
            accounting = positionAccounting.applySell(instrumentId, command.symbol(), command.productType(),
                command.quantity(), netAmount, now);
            cashAfter = money(account.cash().add(netAmount));
        }

        accounts.updateCash(cashAfter, now);
        accounts.appendCashEntry(orderId,
            command.side() == Side.BUY ? "BUY_SETTLEMENT" : "SELL_SETTLEMENT",
            netAmount, cashAfter, now);

        fills.appendFill(orderId, 1, new FillWrite(
            command.symbol(), command.side(), command.quantity(), command.entryPrice(),
            decision.effectiveFillPrice(), decision.grossNotional(), decision.estimatedFee(),
            decision.estimatedSlippage(), netAmount, accounting.appliedCostBasis(),
            accounting.realizedDelta(), EXECUTION_MODEL_VERSION, LIQUIDITY_ASSUMPTION, now));
        orders.updateFillSummary(orderId, command.quantity(), decision.effectiveFillPrice(), now);

        return new ExecutionResult(cashAfter, accounting.appliedCostBasis(), accounting.realizedDelta());
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record ExecutionResult(BigDecimal cashAfter, BigDecimal appliedCostBasis, BigDecimal realizedPnl) {}
}
