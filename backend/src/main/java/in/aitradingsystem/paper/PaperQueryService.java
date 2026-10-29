package in.aitradingsystem.paper;

import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.config.RiskProperties;
import in.aitradingsystem.paper.PaperViews.DecisionView;
import in.aitradingsystem.paper.PaperViews.EquityPoint;
import in.aitradingsystem.paper.PaperViews.OrderView;
import in.aitradingsystem.paper.PaperViews.PortfolioView;
import in.aitradingsystem.paper.PaperViews.PositionView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only views over the persisted paper ledger. All figures are derived from stored rows. */
@Service
@Transactional(readOnly = true)
public class PaperQueryService {
    static final String MARK_SOURCE = "LAST_PAPER_FILL";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final JdbcClient jdbc;
    private final RiskProperties props;
    private final PaperTradingService trading;
    private final Clock clock;

    public PaperQueryService(JdbcClient jdbc, RiskProperties props, PaperTradingService trading, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.trading = trading;
        this.clock = clock;
    }

    private record Account(BigDecimal startingCapital, BigDecimal cash) {}

    public PortfolioView portfolio() {
        Account account = jdbc.sql("SELECT starting_capital, cash_balance FROM paper_account WHERE id = 1")
            .query((rs, n) -> new Account(rs.getBigDecimal("starting_capital"), rs.getBigDecimal("cash_balance")))
            .single();

        List<PositionView> positions = positions();
        BigDecimal invested = ZERO;
        BigDecimal marketValue = ZERO;
        BigDecimal unrealized = ZERO;
        for (PositionView p : positions) {
            invested = invested.add(p.costBasis());
            marketValue = marketValue.add(p.marketValue());
            unrealized = unrealized.add(p.unrealizedPnl());
        }
        BigDecimal equity = PaperExecutionModel.money(account.cash().add(marketValue));
        BigDecimal exposure = equity.signum() == 0 ? ZERO
            : marketValue.multiply(BigDecimal.valueOf(100)).divide(equity, 2, RoundingMode.HALF_UP);

        BigDecimal realized = money(jdbc.sql("SELECT COALESCE(SUM(realized_pnl), 0) FROM paper_fill")
            .query(BigDecimal.class).single());
        BigDecimal realizedToday = money(jdbc.sql("SELECT COALESCE(SUM(realized_pnl), 0) FROM paper_fill WHERE filled_at >= :start")
            .param("start", PaperTradingService.ts(startOfToday())).query(BigDecimal.class).single());
        BigDecimal ledgerTotal = money(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM paper_cash_entry")
            .query(BigDecimal.class).single());

        return new PortfolioView("PAPER", false, trading.isHalted(), account.startingCapital(), account.cash(),
            money(invested), money(marketValue), equity, realized, money(unrealized), realizedToday, exposure,
            positions.size(), ledgerTotal.compareTo(account.cash()) == 0, MARK_SOURCE, positions, Instant.now(clock));
    }

    public List<PositionView> positions() {
        return jdbc.sql("""
                SELECT p.instrument_id, p.exchange, p.symbol, p.product_type, p.quantity, p.cost_basis,
                       p.average_price, p.stop_loss, p.target_price, p.opened_at,
                       (SELECT f.fill_price
                          FROM paper_fill f
                          JOIN paper_order o ON o.id = f.order_id
                         WHERE o.symbol = p.symbol AND o.product_type = p.product_type
                           AND (p.instrument_id IS NULL OR o.instrument_id = p.instrument_id)
                         ORDER BY f.filled_at DESC, f.fill_sequence DESC, f.id DESC
                         LIMIT 1) AS mark_price
                  FROM paper_position p
                 ORDER BY p.symbol, p.product_type, p.instrument_id NULLS LAST
                """).query((rs, n) -> {
                    int qty = rs.getInt("quantity");
                    BigDecimal cost = rs.getBigDecimal("cost_basis");
                    BigDecimal average = rs.getBigDecimal("average_price");
                    if (average == null) average = cost.divide(BigDecimal.valueOf(qty), 4, RoundingMode.HALF_UP);
                    BigDecimal mark = rs.getBigDecimal("mark_price");
                    if (mark == null) mark = average;
                    BigDecimal value = PaperExecutionModel.money(mark.multiply(BigDecimal.valueOf(qty)));
                    return new PositionView(rs.getObject("instrument_id", Long.class), rs.getString("exchange"),
                        rs.getString("symbol"), rs.getString("product_type"), qty,
                        PaperExecutionModel.money(average), cost, mark, value,
                        PaperExecutionModel.money(value.subtract(cost)), rs.getBigDecimal("stop_loss"),
                        rs.getBigDecimal("target_price"), PaperTradingService.instant(rs, "opened_at"));
                }).list();
    }

    public PageResponse<OrderView> orders(String status, String symbol, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) where.append(" AND status = :status");
        if (symbol != null) where.append(" AND symbol = :symbol");
        var count = jdbc.sql("SELECT COUNT(*) FROM paper_order" + where);
        var rows = jdbc.sql("SELECT * FROM paper_order" + where + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset");
        if (status != null) { count = count.param("status", status); rows = rows.param("status", status); }
        if (symbol != null) { count = count.param("symbol", symbol); rows = rows.param("symbol", symbol); }
        long total = count.query(Long.class).single();
        List<OrderView> items = rows.param("limit", size).param("offset", (long) page * size)
            .query((rs, n) -> PaperTradingService.toOrderView(rs)).list();
        return PageResponse.of(items, page, size, total);
    }

    public PaperViews.OrderResult order(java.util.UUID id) {
        return trading.loadResult(id, false);
    }

    public PageResponse<DecisionView> decisions(int page, int size) {
        long total = jdbc.sql("SELECT COUNT(*) FROM risk_decision").query(Long.class).single();
        List<DecisionView> items = jdbc.sql("SELECT * FROM risk_decision ORDER BY evaluated_at DESC, id LIMIT :limit OFFSET :offset")
            .param("limit", size).param("offset", (long) page * size)
            .query((rs, n) -> PaperTradingService.toDecisionView(rs)).list();
        return PageResponse.of(items, page, size, total);
    }

    /** Realized equity by Asia/Kolkata trading day. Only fills are counted; no mark-to-market is applied. */
    public List<EquityPoint> equityCurve(int days) {
        LocalDate today = LocalDate.now(clock.withZone(PaperTradingService.MARKET_ZONE));
        Instant from = today.minusDays(days - 1L).atStartOfDay(PaperTradingService.MARKET_ZONE).toInstant();
        BigDecimal startingCapital = jdbc.sql("SELECT starting_capital FROM paper_account WHERE id = 1")
            .query(BigDecimal.class).single();
        BigDecimal cumulative = money(jdbc.sql("SELECT COALESCE(SUM(realized_pnl), 0) FROM paper_fill WHERE filled_at < :from")
            .param("from", PaperTradingService.ts(from)).query(BigDecimal.class).single());
        List<EquityPoint> points = new ArrayList<>();
        jdbc.sql("""
                SELECT (filled_at AT TIME ZONE 'Asia/Kolkata')::date AS day, SUM(realized_pnl) AS pnl
                FROM paper_fill WHERE filled_at >= :from GROUP BY day ORDER BY day
                """).param("from", PaperTradingService.ts(from))
            .query((rs, n) -> new Object[] {rs.getObject("day", LocalDate.class), rs.getBigDecimal("pnl")})
            .list()
            .forEach(row -> {
                BigDecimal dayPnl = money((BigDecimal) row[1]);
                points.add(new EquityPoint(row[0].toString(), dayPnl, null, null));
            });
        List<EquityPoint> curve = new ArrayList<>();
        for (EquityPoint p : points) {
            cumulative = cumulative.add(p.realizedPnl());
            curve.add(new EquityPoint(p.date(), p.realizedPnl(), cumulative,
                PaperExecutionModel.money(startingCapital.add(cumulative))));
        }
        return curve;
    }

    public RiskProperties riskProperties() {
        return props;
    }

    private Instant startOfToday() {
        return LocalDate.now(clock.withZone(PaperTradingService.MARKET_ZONE))
            .atStartOfDay(PaperTradingService.MARKET_ZONE).toInstant();
    }

    private static BigDecimal money(BigDecimal value) {
        return PaperExecutionModel.money(value);
    }
}
