package in.aitradingsystem.risk;

import in.aitradingsystem.common.CorrelationIdFilter;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import in.aitradingsystem.marketdata.MarketSession;
import in.aitradingsystem.paper.OrderCommand;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Builds server-owned risk state inside the existing PAPER order transaction. */
@Service
public class RiskContextFactory {
    private final JdbcClient jdbc;
    private final RiskPolicyConfigRepository policies;
    private final IndianCashMarketCalendar calendar;

    public RiskContextFactory(JdbcClient jdbc, RiskPolicyConfigRepository policies,
                              IndianCashMarketCalendar calendar) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.calendar = calendar;
    }

    public RiskContext build(UUID orderId, OrderCommand command, Instant now) {
        if (orderId == null || command == null || now == null) {
            throw new IllegalArgumentException("orderId, command and now are required");
        }

        Account account = jdbc.sql("SELECT starting_capital, cash_balance FROM paper_account WHERE id = 1")
                .query((rs, row) -> new Account(rs.getBigDecimal("starting_capital"), rs.getBigDecimal("cash_balance")))
                .optional().orElseThrow(() -> new IllegalStateException("Paper account is not initialized"));
        InstrumentIdentity instrument = resolveInstrument(command.symbol());
        Position position = position(command.symbol());
        BigDecimal currentGrossExposure = currentGrossExposure();
        BigDecimal currentEquity = account.cash().add(currentGrossExposure);
        BigDecimal symbolExposure = position.marketValue();
        Instant dayStart = now.atZone(IndianCashMarketCalendar.ZONE).toLocalDate()
                .atStartOfDay(IndianCashMarketCalendar.ZONE).toInstant();

        int openPositions = jdbc.sql("SELECT COUNT(*) FROM paper_position").query(Integer.class).single();
        BigDecimal realizedToday = jdbc.sql("SELECT COALESCE(SUM(realized_pnl), 0) FROM paper_fill WHERE filled_at >= :from")
                .param("from", OffsetDateTime.ofInstant(dayStart, java.time.ZoneOffset.UTC))
                .query(BigDecimal.class).single();
        int tradesToday = jdbc.sql("SELECT COUNT(*) FROM paper_fill WHERE filled_at >= :from")
                .param("from", OffsetDateTime.ofInstant(dayStart, java.time.ZoneOffset.UTC))
                .query(Integer.class).single();
        boolean duplicatePending = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM paper_order
                     WHERE symbol = :symbol AND id <> :orderId
                       AND status IN ('NEW','RISK_APPROVED')
                )
                """).param("symbol", command.symbol()).param("orderId", orderId).query(Boolean.class).single();
        boolean halted = jdbc.sql("SELECT new_orders_halted FROM trading_control WHERE id = 1")
                .query(Boolean.class).single();

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId == null || correlationId.isBlank()) correlationId = "paper-" + orderId;
        return new RiskContext(
                orderId,
                correlationId,
                ExecutionMode.PAPER,
                command.side(),
                instrument.instrumentId(),
                instrument.exchange(),
                command.symbol(),
                "PAPER_MANUAL",
                TradingStyle.DELIVERY,
                command.quantity(),
                command.entryPrice(),
                command.stopLoss(),
                command.target(),
                command.signalGeneratedAt(),
                now,
                now,
                account.startingCapital(),
                account.cash(),
                currentEquity,
                realizedToday,
                position.quantity(),
                position.marketValue(),
                openPositions,
                currentGrossExposure,
                symbolExposure,
                tradesToday,
                duplicatePending,
                halted,
                marketSessionState(now),
                instrument.eligible(),
                policies.requireActive());
    }

    private InstrumentIdentity resolveInstrument(String symbol) {
        return jdbc.sql("""
                SELECT i.instrument_id, i.exchange, i.instrument_type
                  FROM instrument_master i
                  JOIN instrument_catalogue_state s
                    ON s.singleton_id = 1 AND s.active_version = i.catalogue_version
                 WHERE i.active = TRUE AND upper(i.trading_symbol) = upper(:symbol)
                 ORDER BY CASE WHEN i.instrument_type = 'EQ' THEN 0 ELSE 1 END, i.instrument_id
                 LIMIT 1
                """).param("symbol", symbol)
                .query((rs, row) -> new InstrumentIdentity(
                        rs.getLong("instrument_id"), rs.getString("exchange"),
                        "EQ".equalsIgnoreCase(rs.getString("instrument_type"))))
                .optional()
                .orElse(new InstrumentIdentity(syntheticInstrumentId(symbol), "PAPER", true));
    }

    private Position position(String symbol) {
        return jdbc.sql("SELECT quantity, cost_basis FROM paper_position WHERE symbol = :symbol")
                .param("symbol", symbol)
                .query((rs, row) -> new Position(rs.getInt("quantity"), rs.getBigDecimal("cost_basis")))
                .optional().orElse(new Position(0, BigDecimal.ZERO.setScale(2)));
    }

    private BigDecimal currentGrossExposure() {
        BigDecimal value = jdbc.sql("SELECT COALESCE(SUM(cost_basis), 0) FROM paper_position")
                .query(BigDecimal.class).single();
        return value == null ? BigDecimal.ZERO.setScale(2) : value;
    }

    private MarketSessionState marketSessionState(Instant now) {
        var date = now.atZone(IndianCashMarketCalendar.ZONE).toLocalDate();
        MarketSession session = calendar.session(date);
        if (!session.tradingDay()) return MarketSessionState.CLOSED_DAY;
        if (now.isBefore(session.open().toInstant())) return MarketSessionState.PRE_OPEN;
        if (now.isBefore(session.close().toInstant())) return MarketSessionState.OPEN;
        return MarketSessionState.POST_CLOSE;
    }

    private static long syntheticInstrumentId(String symbol) {
        long hash = Integer.toUnsignedLong(symbol.toUpperCase(java.util.Locale.ROOT).hashCode());
        return Math.max(1L, hash);
    }

    private record Account(BigDecimal startingCapital, BigDecimal cash) {}
    private record Position(int quantity, BigDecimal marketValue) {}
    private record InstrumentIdentity(long instrumentId, String exchange, boolean eligible) {}
}
