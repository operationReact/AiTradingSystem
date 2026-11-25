package in.aitradingsystem.mind;

import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.scanner.OpeningRangeBreakoutStrategy;
import in.aitradingsystem.scanner.ScanCandidate;
import in.aitradingsystem.scanner.ScanContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Fixed-protocol, cash-equity ORB historical simulation.
 * Signals use only bars through their close; entry is the next bar open.
 * Costs are illustrative research assumptions, not broker charge confirmation.
 */
@Component
public final class MindOrbOutOfSampleEvaluator {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal STARTING_CAPITAL = new BigDecimal("100000");
    private static final BigDecimal FEE_PER_SIDE = new BigDecimal("0.0010");
    private static final BigDecimal SLIPPAGE_PER_SIDE = new BigDecimal("0.0005");
    private static final BigDecimal RISK_PERCENT = new BigDecimal("0.5");
    private static final BigDecimal MAX_NOTIONAL_PERCENT = new BigDecimal("20");
    private static final int MAX_SHARES = 10000;

    public record Metrics(int candles, int closedTrades, int winningTrades,
            BigDecimal grossPnl, BigDecimal feeCost, BigDecimal slippageCost,
            BigDecimal netPnl, BigDecimal returnPercent, BigDecimal maxDrawdownPercent) {}

    public record Assumptions(BigDecimal initialCapital, BigDecimal feePerSide,
            BigDecimal slippagePerSide, BigDecimal riskPerTradePercent,
            BigDecimal maxNotionalPercent, String label) {}

    private record Position(int quantity, BigDecimal rawEntry, BigDecimal executedEntry,
            BigDecimal stop, BigDecimal target, BigDecimal entryFee) {}

    private final OpeningRangeBreakoutStrategy strategy;

    public MindOrbOutOfSampleEvaluator(OpeningRangeBreakoutStrategy strategy) {
        this.strategy = strategy;
    }

    public Assumptions assumptions() {
        return new Assumptions(STARTING_CAPITAL, FEE_PER_SIDE, SLIPPAGE_PER_SIDE,
                RISK_PERCENT, MAX_NOTIONAL_PERCENT,
                "ILLUSTRATIVE_COMPOSITE_COSTS_NOT_ACTUAL_BROKER_OR_STATUTORY_CHARGES");
    }

    public Metrics evaluate(List<MarketCandle> bars, String interval) {
        if (bars == null || interval == null) throw new IllegalArgumentException("Bars and interval required");
        int step = switch (interval) {
            case "ONE_MINUTE" -> 1;
            case "THREE_MINUTE" -> 3;
            case "FIVE_MINUTE" -> 5;
            default -> throw new IllegalArgumentException("Unsupported ORB interval");
        };
        if (bars.size() > 50000) throw new IllegalArgumentException("ORB evaluation window exceeds bound");
        for (int i = 0; i < bars.size(); i++) {
            MarketCandle bar = bars.get(i);
            if (bar == null || !interval.equals(bar.interval()) ||
                    (i > 0 && !bar.candleTime().isAfter(bars.get(i - 1).candleTime()))) {
                throw new IllegalArgumentException("ORB candles must be chronological and interval-consistent");
            }
        }

        BigDecimal equity = STARTING_CAPITAL;
        BigDecimal peak = STARTING_CAPITAL;
        BigDecimal worstDrawdown = BigDecimal.ZERO;
        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal feeTotal = BigDecimal.ZERO;
        BigDecimal slippageTotal = BigDecimal.ZERO;
        int trades = 0;
        int winners = 0;
        List<MarketCandle> session = new ArrayList<>();

        for (int i = 0; i <= bars.size(); i++) {
            boolean end = i == bars.size();
            if (!end && !session.isEmpty() && !localDay(session.getFirst()).equals(localDay(bars.get(i)))) {
                var totals = simulateSession(session, interval, step, equity, peak, worstDrawdown,
                        grossTotal, feeTotal, slippageTotal, trades, winners);
                equity = totals.equity(); peak = totals.peak(); worstDrawdown = totals.drawdown();
                grossTotal = totals.gross(); feeTotal = totals.fees();
                slippageTotal = totals.slippage(); trades = totals.trades(); winners = totals.winners();
                session.clear();
            }
            if (end) {
                if (!session.isEmpty()) {
                    var totals = simulateSession(session, interval, step, equity, peak, worstDrawdown,
                            grossTotal, feeTotal, slippageTotal, trades, winners);
                    equity = totals.equity(); peak = totals.peak(); worstDrawdown = totals.drawdown();
                    grossTotal = totals.gross(); feeTotal = totals.fees();
                    slippageTotal = totals.slippage(); trades = totals.trades(); winners = totals.winners();
                }
            } else {
                session.add(bars.get(i));
            }
        }

        BigDecimal net = equity.subtract(STARTING_CAPITAL);
        return new Metrics(bars.size(), trades, winners, money(grossTotal), money(feeTotal),
                money(slippageTotal), money(net), percent(net, STARTING_CAPITAL),
                worstDrawdown.setScale(4, RoundingMode.HALF_UP));
    }

    private record Totals(BigDecimal equity, BigDecimal peak, BigDecimal drawdown,
            BigDecimal gross, BigDecimal fees, BigDecimal slippage, int trades, int winners) {}

    private Totals simulateSession(List<MarketCandle> bars, String interval, int step,
            BigDecimal equity, BigDecimal peak, BigDecimal drawdown,
            BigDecimal grossTotal, BigDecimal feeTotal, BigDecimal slipTotal,
            int trades, int winners) {
        Position held = null;
        for (int i = 0; i < bars.size(); i++) {
            MarketCandle bar = bars.get(i);
            boolean sessionEnd = i == bars.size() - 1;
            boolean exited = false;
            if (held != null) {
                BigDecimal rawExit = exitPrice(held, bar, sessionEnd);
                if (rawExit != null) {
                    BigDecimal actualExit = rawExit.multiply(BigDecimal.ONE.subtract(SLIPPAGE_PER_SIDE));
                    BigDecimal quantity = BigDecimal.valueOf(held.quantity());
                    BigDecimal entrySlip = held.executedEntry().subtract(held.rawEntry()).multiply(quantity);
                    BigDecimal exitSlip = rawExit.subtract(actualExit).multiply(quantity);
                    BigDecimal exitFee = actualExit.multiply(quantity).multiply(FEE_PER_SIDE);
                    BigDecimal gross = rawExit.subtract(held.rawEntry()).multiply(quantity);
                    BigDecimal net = gross.subtract(entrySlip).subtract(exitSlip)
                            .subtract(held.entryFee()).subtract(exitFee);
                    equity = equity.add(net);
                    grossTotal = grossTotal.add(gross);
                    feeTotal = feeTotal.add(held.entryFee()).add(exitFee);
                    slipTotal = slipTotal.add(entrySlip).add(exitSlip);
                    if (net.signum() > 0) winners++;
                    trades++;
                    held = null;
                    exited = true;
                }
            }
            BigDecimal marked = equity;
            if (held != null) {
                BigDecimal exitMark = bar.close().multiply(BigDecimal.ONE.subtract(SLIPPAGE_PER_SIDE));
                BigDecimal quantity = BigDecimal.valueOf(held.quantity());
                marked = equity.add(exitMark.subtract(held.executedEntry()).multiply(quantity))
                        .subtract(held.entryFee()).subtract(exitMark.multiply(quantity).multiply(FEE_PER_SIDE));
            }
            if (marked.compareTo(peak) > 0) peak = marked;
            if (peak.signum() > 0) {
                BigDecimal dd = peak.subtract(marked).max(BigDecimal.ZERO)
                        .multiply(HUNDRED).divide(peak, 8, RoundingMode.HALF_UP);
                if (dd.compareTo(drawdown) > 0) drawdown = dd;
            }

            if (held != null || exited || sessionEnd || i < 1) continue;
            MarketCandle next = bars.get(i + 1);
            if (!next.candleTime().equals(bar.candleTime().plus(Duration.ofMinutes(step)))) continue;
            ScanCandidate signal = strategy.evaluate(new ScanContext(bar.instrumentId(),
                    bar.tradingSymbol(), bar.exchange(), interval, bar.candleTime(),
                    bars.subList(0, i + 1))).orElse(null);
            if (signal != null) held = open(signal, next, equity);
        }
        return new Totals(equity, peak, drawdown, grossTotal, feeTotal, slipTotal, trades, winners);
    }

    private static Position open(ScanCandidate signal, MarketCandle next, BigDecimal equity) {
        BigDecimal raw = next.open();
        if (raw == null || raw.signum() <= 0 || equity.signum() <= 0) return null;
        BigDecimal actual = raw.multiply(BigDecimal.ONE.add(SLIPPAGE_PER_SIDE));
        BigDecimal riskPerShare = actual.subtract(signal.suggestedStop());
        if (riskPerShare.signum() <= 0) return null;
        BigDecimal maxRisk = equity.multiply(RISK_PERCENT).divide(HUNDRED, 8, RoundingMode.DOWN);
        BigDecimal maxNotional = equity.multiply(MAX_NOTIONAL_PERCENT).divide(HUNDRED, 8, RoundingMode.DOWN);
        int qtyRisk = maxRisk.divide(riskPerShare, 0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(MAX_SHARES)).intValue();
        int qtyCapital = maxNotional.divide(actual.multiply(BigDecimal.ONE.add(FEE_PER_SIDE)),
                0, RoundingMode.DOWN).min(BigDecimal.valueOf(MAX_SHARES)).intValue();
        int qty = Math.min(MAX_SHARES, Math.min(qtyRisk, qtyCapital));
        if (qty < 1) return null;
        BigDecimal fee = actual.multiply(BigDecimal.valueOf(qty)).multiply(FEE_PER_SIDE);
        return new Position(qty, raw, actual, signal.suggestedStop(), signal.suggestedTarget(), fee);
    }

    private static BigDecimal exitPrice(Position p, MarketCandle bar, boolean sessionEnd) {
        if (bar.open().compareTo(p.stop()) <= 0) return bar.open();
        if (bar.open().compareTo(p.target()) >= 0) return bar.open();
        if (bar.low().compareTo(p.stop()) <= 0) return p.stop();
        if (bar.high().compareTo(p.target()) >= 0) return p.target();
        return sessionEnd ? bar.close() : null;
    }

    private static LocalDate localDay(MarketCandle candle) {
        return candle.candleTime().atZone(IST).toLocalDate();
    }

    private static BigDecimal percent(BigDecimal amount, BigDecimal base) {
        return amount.multiply(HUNDRED).divide(base, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
