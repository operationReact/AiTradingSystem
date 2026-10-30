package in.aitradingsystem.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Rebuilds closed Asia/Kolkata days from durable fills/cash and point-in-time persisted marks. */
@Service
public class PerformanceAggregationService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PerformanceAggregationService(JdbcClient jdbc, ObjectMapper json, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public DailyPerformance recalculate(LocalDate day) {
        for (int attempt = 0; ; attempt++) {
            try { return transaction.execute(status -> aggregate(day)); }
            catch (ConcurrencyFailureException ex) { if (attempt == 2) throw ex; }
        }
    }

    private DailyPerformance aggregate(LocalDate day) {
        if (day == null || !day.isBefore(LocalDate.now(clock.withZone(IndianCashMarketCalendar.ZONE)))) {
            throw new IllegalArgumentException("A completed Asia/Kolkata trade date is required");
        }
        // Serialize rebuilds of one cache key, without locking the execution account or mutating source ledgers.
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", day.toEpochDay()).query().listOfRows();
        Instant start = day.atStartOfDay(IndianCashMarketCalendar.ZONE).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(IndianCashMarketCalendar.ZONE).toInstant();
        List<Fill> fills = jdbc.sql("""
            SELECT f.*, o.instrument_id, o.product_type, COALESCE(o.strategy, 'UNATTRIBUTED') AS strategy
              FROM paper_fill f JOIN paper_order o ON o.id = f.order_id
             WHERE f.filled_at < :end ORDER BY f.filled_at, f.order_id, f.fill_sequence, f.id
            """).param("end", ts(end)).query((rs, row) -> new Fill(
                rs.getObject("order_id", UUID.class), rs.getString("strategy"),
                new PositionKey(rs.getObject("instrument_id", Long.class), rs.getString("symbol"), rs.getString("product_type")),
                "BUY".equals(rs.getString("side")), rs.getInt("quantity"), rs.getBigDecimal("cost_basis"),
                rs.getBigDecimal("realized_pnl"), rs.getBigDecimal("fee_amount"), rs.getBigDecimal("slippage_amount"),
                rs.getBigDecimal("net_amount"), rs.getObject("filled_at", OffsetDateTime.class).toInstant())).list();

        List<Map<String, Object>> evidence = new ArrayList<>();
        Map<PositionKey, BigDecimal> openingMarks = new HashMap<>();
        Map<PositionKey, BigDecimal> closingMarks = new HashMap<>();
        Book opening = book(fills.stream().filter(f -> f.at().isBefore(start)).toList());
        Book closing = book(fills);
        BigDecimal openingValue = marketValue(opening, start, openingMarks, evidence);
        BigDecimal closingValue = marketValue(closing, end, closingMarks, evidence);
        BigDecimal openingCash = cashBefore(start);
        BigDecimal closingCash = cashBefore(end);
        BigDecimal capital = jdbc.sql("""
            SELECT COALESCE(sum(amount),0) FROM paper_cash_entry
             WHERE entry_type = 'CAPITAL_SEED' AND created_at >= :start AND created_at < :end
            """).param("start", ts(start)).param("end", ts(end)).query(BigDecimal.class).single();
        Totals totals = totals(fills, start);
        BigDecimal openingEquity = money(openingCash.add(openingValue));
        BigDecimal closingEquity = money(closingCash.add(closingValue));
        BigDecimal net = money(closingEquity.subtract(openingEquity).subtract(capital));
        BigDecimal unrealized = money(closingValue.subtract(closing.cost()));
        BigDecimal expected = money(totals.realized().add(unrealized).subtract(openingValue.subtract(opening.cost())));
        if (net.compareTo(expected) != 0) {
            throw new IllegalStateException("PERFORMANCE_LEDGER_MISMATCH: cash, fills and valuation do not reconcile");
        }
        BigDecimal percent = openingEquity.signum() > 0
            ? net.multiply(new BigDecimal("100")).divide(openingEquity, 6, RoundingMode.HALF_UP) : null;
        List<BigDecimal> samples = jdbc.sql("""
            SELECT equity FROM paper_portfolio_snapshot WHERE snapshot_at >= :start AND snapshot_at < :end
             ORDER BY snapshot_at, id
            """).param("start", ts(start)).param("end", ts(end)).query(BigDecimal.class).list();
        // Deposits can change equity without investment return; do not present an unadjusted drawdown for that day.
        BigDecimal drawdown = capital.signum() == 0 ? sampledDrawdown(openingEquity, samples) : null;
        DailyPerformance result = new DailyPerformance(day, openingEquity, closingEquity, money(capital),
            money(totals.realized()), unrealized, money(totals.fees()), money(totals.slippage()),
            money(net.add(totals.fees()).add(totals.slippage())), net, percent, drawdown,
            totals.orders().size(), totals.wins(), totals.losses());
        persist(result, evidence, drawdown == null ? "UNAVAILABLE" : "PERSISTED_EQUITY_SAMPLES");

        jdbc.sql("DELETE FROM strategy_performance_daily WHERE trade_date = :day").param("day", day).update();
        Set<String> strategies = new TreeSet<>();
        fills.forEach(f -> strategies.add(f.strategy()));
        for (String strategy : strategies) {
            List<Fill> own = fills.stream().filter(f -> strategy.equals(f.strategy())).toList();
            Book before = book(own.stream().filter(f -> f.at().isBefore(start)).toList());
            Book after = book(own);
            BigDecimal beforeValue = marketValue(before, start, openingMarks, evidence);
            BigDecimal afterValue = marketValue(after, end, closingMarks, evidence);
            Totals t = totals(own, start);
            BigDecimal ownUnrealized = money(afterValue.subtract(after.cost()));
            BigDecimal ownNet = money(t.realized().add(ownUnrealized).subtract(beforeValue.subtract(before.cost())));
            jdbc.sql("""
                INSERT INTO strategy_performance_daily (trade_date,strategy,realized_pnl,unrealized_pnl,fees,slippage,
                    gross_pnl,net_pnl,trade_count,win_count,loss_count)
                VALUES (:day,:strategy,:realized,:unrealized,:fees,:slippage,:gross,:net,:trades,:wins,:losses)
                """).param("day", day).param("strategy", strategy).param("realized", money(t.realized()))
                .param("unrealized", ownUnrealized).param("fees", money(t.fees())).param("slippage", money(t.slippage()))
                .param("gross", money(ownNet.add(t.fees()).add(t.slippage()))).param("net", ownNet)
                .param("trades", t.orders().size()).param("wins", t.wins()).param("losses", t.losses()).update();
        }
        return result;
    }

    private BigDecimal cashBefore(Instant at) {
        return jdbc.sql("SELECT COALESCE(sum(amount),0) FROM paper_cash_entry WHERE created_at < :at")
            .param("at", ts(at)).query(BigDecimal.class).single();
    }

    private Book book(List<Fill> fills) {
        Map<PositionKey, Holding> positions = new HashMap<>();
        for (Fill f : fills) {
            Holding old = positions.getOrDefault(f.position(), new Holding(0, ZERO));
            positions.put(f.position(), new Holding(old.quantity() + (f.buy() ? f.quantity() : -f.quantity()),
                old.cost().add(f.buy() ? f.cost() : f.cost().negate())));
        }
        for (Holding h : positions.values()) {
            if (h.quantity() < 0 || h.cost().signum() < 0 || (h.quantity() == 0 && h.cost().signum() != 0)) {
                throw new IllegalStateException("PERFORMANCE_ATTRIBUTION_UNAVAILABLE: inconsistent or cross-strategy inventory");
            }
        }
        return new Book(positions);
    }

    private BigDecimal marketValue(Book book, Instant before, Map<PositionKey, BigDecimal> marks,
                                   List<Map<String, Object>> evidence) {
        BigDecimal value = ZERO;
        for (var entry : book.positions().entrySet()) {
            if (entry.getValue().quantity() == 0) continue;
            PositionKey key = entry.getKey();
            BigDecimal mark = marks.computeIfAbsent(key, ignored -> {
                if (key.instrumentId() == null) throw new IllegalStateException("PERFORMANCE_MARK_UNAVAILABLE: canonical instrument required");
                var quote = jdbc.sql("""
                    SELECT candle_id, close_price, candle_time FROM market_candle
                     WHERE instrument_id = :id AND candle_time < :at ORDER BY candle_time DESC,candle_id DESC LIMIT 1
                    """).param("id", key.instrumentId()).param("at", ts(before))
                    .query((rs, row) -> new Mark(rs.getLong("candle_id"), rs.getBigDecimal("close_price"),
                        rs.getObject("candle_time", OffsetDateTime.class).toInstant())).optional()
                    .orElseThrow(() -> new IllegalStateException("PERFORMANCE_MARK_UNAVAILABLE: " + key.symbol()));
                evidence.add(Map.of("symbol", key.symbol(), "productType", key.product(), "instrumentId", key.instrumentId(),
                    "candleId", quote.id(), "markAsOf", quote.at().toString(), "boundary", before.toString(),
                    "basis", "LAST_PERSISTED_MARK", "markPrice", quote.price()));
                return quote.price();
            });
            value = value.add(mark.multiply(BigDecimal.valueOf(entry.getValue().quantity())));
        }
        return money(value);
    }

    private Totals totals(List<Fill> fills, Instant start) {
        BigDecimal realized = ZERO, fees = ZERO, slippage = ZERO;
        Set<UUID> orders = new HashSet<>();
        Map<UUID, BigDecimal> exits = new HashMap<>();
        for (Fill f : fills) {
            if (f.at().isBefore(start)) continue;
            realized = realized.add(f.realized()); fees = fees.add(f.fees()); slippage = slippage.add(f.slippage());
            orders.add(f.orderId());
            if (!f.buy()) exits.merge(f.orderId(), f.realized(), BigDecimal::add);
        }
        int wins = (int) exits.values().stream().filter(p -> p.signum() > 0).count();
        int losses = (int) exits.values().stream().filter(p -> p.signum() < 0).count();
        return new Totals(realized, fees, slippage, orders, wins, losses);
    }

    static BigDecimal sampledDrawdown(BigDecimal opening, List<BigDecimal> samples) {
        if (samples.size() < 2 || opening.signum() <= 0) return null;
        BigDecimal peak = opening, maximum = BigDecimal.ZERO;
        for (BigDecimal equity : samples) {
            peak = peak.max(equity);
            if (peak.signum() > 0) maximum = maximum.max(peak.subtract(equity).multiply(new BigDecimal("100"))
                .divide(peak, 6, RoundingMode.HALF_UP));
        }
        return maximum;
    }

    private void persist(DailyPerformance r, List<Map<String, Object>> evidence, String basis) {
        jdbc.sql("""
            INSERT INTO portfolio_performance_daily (trade_date,opening_equity,closing_equity,capital_flow,
                realized_pnl,unrealized_pnl,fees,slippage,gross_pnl,net_pnl,return_percent,
                max_intraday_drawdown_percent,drawdown_basis,trade_count,win_count,loss_count,valuation_evidence)
            VALUES (:day,:opening,:closing,:capital,:realized,:unrealized,:fees,:slippage,:gross,:net,:returns,
                :drawdown,:basis,:trades,:wins,:losses,CAST(:evidence AS jsonb))
            ON CONFLICT (trade_date) DO UPDATE SET opening_equity=EXCLUDED.opening_equity,
                closing_equity=EXCLUDED.closing_equity,capital_flow=EXCLUDED.capital_flow,
                realized_pnl=EXCLUDED.realized_pnl,unrealized_pnl=EXCLUDED.unrealized_pnl,fees=EXCLUDED.fees,
                slippage=EXCLUDED.slippage,gross_pnl=EXCLUDED.gross_pnl,net_pnl=EXCLUDED.net_pnl,
                return_percent=EXCLUDED.return_percent,max_intraday_drawdown_percent=EXCLUDED.max_intraday_drawdown_percent,
                drawdown_basis=EXCLUDED.drawdown_basis,trade_count=EXCLUDED.trade_count,win_count=EXCLUDED.win_count,
                loss_count=EXCLUDED.loss_count,valuation_evidence=EXCLUDED.valuation_evidence,updated_at=now()
            """).param("day", r.tradeDate()).param("opening", r.openingEquity()).param("closing", r.closingEquity())
            .param("capital", r.capitalFlow()).param("realized", r.realizedPnl()).param("unrealized", r.unrealizedPnl())
            .param("fees", r.fees()).param("slippage", r.slippage()).param("gross", r.grossPnl()).param("net", r.netPnl())
            .param("returns", r.returnPercent(), java.sql.Types.NUMERIC)
            .param("drawdown", r.maxIntradayDrawdownPercent(), java.sql.Types.NUMERIC).param("basis", basis)
            .param("trades", r.tradeCount()).param("wins", r.winCount()).param("losses", r.lossCount())
            .param("evidence", encode(evidence)).update();
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private static OffsetDateTime ts(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private record PositionKey(Long instrumentId, String symbol, String product) {}
    private record Holding(int quantity, BigDecimal cost) {}
    private record Book(Map<PositionKey, Holding> positions) {
        BigDecimal cost() { return positions.values().stream().map(Holding::cost).reduce(ZERO, BigDecimal::add); }
    }
    private record Mark(long id, BigDecimal price, Instant at) {}
    private record Fill(UUID orderId, String strategy, PositionKey position, boolean buy, int quantity,
                        BigDecimal cost, BigDecimal realized, BigDecimal fees, BigDecimal slippage,
                        BigDecimal net, Instant at) {}
    private record Totals(BigDecimal realized, BigDecimal fees, BigDecimal slippage, Set<UUID> orders, int wins, int losses) {}
    public record DailyPerformance(LocalDate tradeDate, BigDecimal openingEquity, BigDecimal closingEquity,
        BigDecimal capitalFlow, BigDecimal realizedPnl, BigDecimal unrealizedPnl, BigDecimal fees, BigDecimal slippage,
        BigDecimal grossPnl, BigDecimal netPnl, BigDecimal returnPercent, BigDecimal maxIntradayDrawdownPercent,
        int tradeCount, int winCount, int lossCount) {}
}
