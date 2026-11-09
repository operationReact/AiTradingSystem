package in.aitradingsystem.mind;

import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.scanner.MovingAverageReclaimStrategy;
import in.aitradingsystem.scanner.ScanCandidate;
import in.aitradingsystem.scanner.ScanContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Fixed-protocol historical cash-equity execution simulation for MA_RECLAIM_V1.
 * No training optimization. A 21-candle close signal can only enter at the
 * immediately following real candle's OPEN; stops win ambiguous OHLC bars;
 * positions always close at the last observed bar of the session.
 *
 * Fee/slippage assumptions are deliberate research estimates, NOT the actual
 * broker's complete brokerage + statutory charge schedule.
 */
@Component
public final class MindMaWalkForwardEvaluator {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal STARTING_CAPITAL = new BigDecimal("100000");
    private static final BigDecimal FEE_PER_SIDE = new BigDecimal("0.0010");
    private static final BigDecimal SLIPPAGE_PER_SIDE = new BigDecimal("0.0005");
    private static final BigDecimal RISK_PERCENT = new BigDecimal("0.5");
    private static final BigDecimal MAX_NOTIONAL_PERCENT = new BigDecimal("20");
    private static final int MAX_SHARES = 10000;
    private final MovingAverageReclaimStrategy strategy;

    public MindMaWalkForwardEvaluator(MovingAverageReclaimStrategy strategy) {
        this.strategy = strategy;
    }

    public record Metrics(
        int candles, int closedTrades, int winningTrades,
        BigDecimal grossPnl, BigDecimal feeCost, BigDecimal slippageCost,
        BigDecimal netPnl, BigDecimal returnPercent, BigDecimal maxDrawdownPercent
    ) {}

    public record Assumptions(
        BigDecimal initialCapital, BigDecimal feePerSide, BigDecimal slippagePerSide,
        BigDecimal riskPerTradePercent, BigDecimal maxNotionalPercent, String label
    ) {}

    public Assumptions assumptions() {
        return new Assumptions(STARTING_CAPITAL, FEE_PER_SIDE, SLIPPAGE_PER_SIDE,
            RISK_PERCENT, MAX_NOTIONAL_PERCENT,
            "ILLUSTRATIVE_COMPOSITE_COSTS_NOT_ACTUAL_BROKER_OR_STATUTORY_CHARGES");
    }

    private record Position(
        int quantity, BigDecimal rawEntry, BigDecimal executedEntry,
        BigDecimal stop, BigDecimal target, BigDecimal entryFee
    ) {}

    public Metrics evaluate(List<MarketCandle> bars) {
        if (bars == null) throw new IllegalArgumentException("Candles required");
        if (bars.size() < 22) return zero(bars.size());
        BigDecimal equity = STARTING_CAPITAL;
        BigDecimal peak = STARTING_CAPITAL;
        BigDecimal worstDrawdown = BigDecimal.ZERO;
        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal feeTotal = BigDecimal.ZERO;
        BigDecimal slippageTotal = BigDecimal.ZERO;
        int trades = 0;
        int winners = 0;
        Position held = null;
        ScanCandidate pending = null;

        for (int i = 0; i < bars.size(); i++) {
            MarketCandle bar = bars.get(i);
            boolean endSession = i == bars.size() - 1 ||
                !localDay(bar).equals(localDay(bars.get(i + 1)));
            boolean exited = false;

            if (pending != null) {
                // Pending signal was determined solely at the PRIOR close.
                held = open(pending, bar, equity);
                pending = null;
            }
            if (held != null) {
                BigDecimal rawExit = exitPrice(held, bar, endSession);
                if (rawExit != null) {
                    BigDecimal actualExit = rawExit.multiply(
                        BigDecimal.ONE.subtract(SLIPPAGE_PER_SIDE));
                    BigDecimal qty = BigDecimal.valueOf(held.quantity());
                    BigDecimal entrySlip = held.executedEntry()
                        .subtract(held.rawEntry()).multiply(qty);
                    BigDecimal exitSlip = rawExit.subtract(actualExit).multiply(qty);
                    BigDecimal exitFee = actualExit.multiply(qty).multiply(FEE_PER_SIDE);
                    BigDecimal gross = rawExit.subtract(held.rawEntry()).multiply(qty);
                    BigDecimal totalCost = entrySlip.add(exitSlip)
                        .add(held.entryFee()).add(exitFee);
                    BigDecimal net = gross.subtract(totalCost);
                    equity = equity.add(net);
                    grossTotal = grossTotal.add(gross);
                    feeTotal = feeTotal.add(held.entryFee()).add(exitFee);
                    slippageTotal = slippageTotal.add(entrySlip).add(exitSlip);
                    winners += net.signum() > 0 ? 1 : 0;
                    trades++;
                    held = null;
                    exited = true;
                }
            }

            BigDecimal marked = equity;
            if (held != null) {
                // Conservative liquidation mark for intrabar risk visibility.
                BigDecimal qty = BigDecimal.valueOf(held.quantity());
                BigDecimal exitMark = bar.close().multiply(
                    BigDecimal.ONE.subtract(SLIPPAGE_PER_SIDE));
                marked = equity.add(exitMark.subtract(held.executedEntry()).multiply(qty))
                    .subtract(held.entryFee())
                    .subtract(exitMark.multiply(qty).multiply(FEE_PER_SIDE));
            }
            if (marked.compareTo(peak) > 0) peak = marked;
            if (peak.signum() > 0) {
                BigDecimal dd = peak.subtract(marked).max(BigDecimal.ZERO)
                    .multiply(HUNDRED).divide(peak, 8, RoundingMode.HALF_UP);
                if (dd.compareTo(worstDrawdown) > 0) worstDrawdown = dd;
            }

            // Only one trade at a time. No same-candle re-entry.
            if (held != null || exited || endSession || i < 20) continue;
            MarketCandle next = bars.get(i + 1);
            if (!contiguousSameSession(bars, i - 20, i) ||
                !next.candleTime().equals(bar.candleTime().plus(Duration.ofMinutes(5))) ||
                !localDay(next).equals(localDay(bar))) continue;

            // The same concrete ScannerStrategy implementation as the scanner.
            pending = strategy.evaluate(new ScanContext(
                bar.instrumentId(), bar.tradingSymbol(), bar.exchange(),
                bar.interval(), bar.candleTime(),
                bars.subList(i - 20, i + 1))).orElse(null);
        }
        BigDecimal net = equity.subtract(STARTING_CAPITAL);
        return new Metrics(bars.size(), trades, winners,
            money(grossTotal), money(feeTotal), money(slippageTotal),
            money(net), percent(net, STARTING_CAPITAL),
            worstDrawdown.setScale(4, RoundingMode.HALF_UP));
    }

    private static Position open(ScanCandidate signal, MarketCandle next, BigDecimal equity) {
        BigDecimal raw = next.open();
        BigDecimal actual = raw.multiply(BigDecimal.ONE.add(SLIPPAGE_PER_SIDE));
        BigDecimal riskPerShare = actual.subtract(signal.suggestedStop());
        if (riskPerShare.signum() <= 0 || raw.signum() <= 0 || equity.signum() <= 0)
            return null;
        BigDecimal maxRisk = equity.multiply(RISK_PERCENT).divide(HUNDRED, 8, RoundingMode.DOWN);
        BigDecimal maxNotional = equity.multiply(MAX_NOTIONAL_PERCENT)
            .divide(HUNDRED, 8, RoundingMode.DOWN);
        int qtyRisk = maxRisk.divide(riskPerShare, 0, RoundingMode.DOWN).intValue();
        int qtyCapital = maxNotional.divide(
            actual.multiply(BigDecimal.ONE.add(FEE_PER_SIDE)), 0, RoundingMode.DOWN).intValue();
        int qty = Math.min(MAX_SHARES, Math.min(qtyRisk, qtyCapital));
        if (qty < 1) return null;
        BigDecimal entryFee = actual.multiply(BigDecimal.valueOf(qty)).multiply(FEE_PER_SIDE);
        return new Position(qty, raw, actual,
            signal.suggestedStop(), signal.suggestedTarget(), entryFee);
    }

    private static BigDecimal exitPrice(Position p, MarketCandle bar, boolean sessionEnd) {
        // Gap through stop is worse than the stop; both-hit bar assumes stop.
        if (bar.open().compareTo(p.stop()) <= 0) return bar.open();
        if (bar.open().compareTo(p.target()) >= 0) return bar.open();
        if (bar.low().compareTo(p.stop()) <= 0) return p.stop();
        if (bar.high().compareTo(p.target()) >= 0) return p.target();
        return sessionEnd ? bar.close() : null;
    }

    private static boolean contiguousSameSession(List<MarketCandle> bars, int start, int end) {
        LocalDate day = localDay(bars.get(end));
        for (int i = start; i <= end; i++) {
            if (!day.equals(localDay(bars.get(i)))) return false;
            if (i > start && !bars.get(i).candleTime().equals(
                    bars.get(i - 1).candleTime().plus(Duration.ofMinutes(5)))) return false;
        }
        return true;
    }

    private static LocalDate localDay(MarketCandle c) {
        return c.candleTime().atZone(IST).toLocalDate();
    }

    private static BigDecimal percent(BigDecimal p, BigDecimal base) {
        return p.multiply(HUNDRED).divide(base, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static Metrics zero(int count) {
        return new Metrics(count, 0, 0, money(BigDecimal.ZERO),
            money(BigDecimal.ZERO), money(BigDecimal.ZERO),
            money(BigDecimal.ZERO), percent(BigDecimal.ZERO, STARTING_CAPITAL),
            BigDecimal.ZERO.setScale(4));
    }
}
