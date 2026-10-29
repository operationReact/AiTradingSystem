package in.aitradingsystem.paper;

import in.aitradingsystem.common.CorrelationIdFilter;
import in.aitradingsystem.common.NotFoundException;
import in.aitradingsystem.domain.OrderStatus;
import in.aitradingsystem.paper.PaperViews.DecisionView;
import in.aitradingsystem.paper.PaperViews.FillView;
import in.aitradingsystem.paper.PaperViews.OrderResult;
import in.aitradingsystem.paper.PaperViews.OrderView;
import in.aitradingsystem.risk.RiskContext;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaperOrderRepository {
    private final JdbcClient jdbc;

    public PaperOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ExistingOrder> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT id, request_hash FROM paper_order WHERE idempotency_key = :key")
            .param("key", key)
            .query((rs, row) -> new ExistingOrder(rs.getObject("id", UUID.class), rs.getString("request_hash")))
            .optional();
    }

    public OrderStatus statusForUpdate(UUID id) {
        return jdbc.sql("SELECT status FROM paper_order WHERE id = :id FOR UPDATE")
            .param("id", id)
            .query(String.class)
            .optional()
            .map(OrderStatus::valueOf)
            .orElseThrow(() -> new NotFoundException("Paper order not found: " + id));
    }

    public void insert(UUID id, String key, String hash, OrderCommand cmd, Instant now) {
        jdbc.sql("""
                INSERT INTO paper_order (id, idempotency_key, request_hash, symbol, side, quantity, submitted_quantity,
                    filled_quantity, order_type, product_type, reference_price, stop_loss, target_price,
                    signal_generated_at, status, correlation_id, created_at, updated_at)
                VALUES (:id, :key, :hash, :symbol, :side, :qty, :qty, 0, 'MARKET', :productType,
                    :ref, :stop, :target, :signal, 'NEW', :cid, :now, :now)
                """)
            .param("id", id).param("key", key).param("hash", hash)
            .param("symbol", cmd.symbol()).param("side", cmd.side().name()).param("qty", cmd.quantity())
            .param("productType", cmd.productType().name()).param("ref", cmd.entryPrice())
            .param("stop", cmd.stopLoss(), Types.NUMERIC).param("target", cmd.target(), Types.NUMERIC)
            .param("signal", cmd.signalGeneratedAt() == null ? null : ts(cmd.signalGeneratedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("cid", MDC.get(CorrelationIdFilter.MDC_KEY), Types.VARCHAR).param("now", ts(now)).update();
    }

    public void enrichIdentity(UUID orderId, RiskContext context, Instant now) {
        Long instrumentId = "PAPER".equalsIgnoreCase(context.exchange()) ? null : context.instrumentId();
        jdbc.sql("""
                UPDATE paper_order
                   SET instrument_id = :instrumentId, exchange = :exchange, strategy = :strategy,
                       product_type = :productType, updated_at = :now
                 WHERE id = :id
                """)
            .param("instrumentId", instrumentId, Types.BIGINT).param("exchange", context.exchange(), Types.VARCHAR)
            .param("strategy", context.strategy(), Types.VARCHAR).param("productType", context.tradingStyle().name())
            .param("now", ts(now)).param("id", orderId).update();
    }

    public void transition(UUID orderId, OrderStatus from, OrderStatus to, String reason, Instant now) {
        from.transitionTo(to);
        int updated = jdbc.sql("UPDATE paper_order SET status = :to, rejection_reason = :reason, updated_at = :now WHERE id = :id AND status = :from")
            .param("to", to.name()).param("reason", reason, Types.VARCHAR).param("now", ts(now))
            .param("id", orderId).param("from", from.name()).update();
        if (updated != 1) throw new IllegalStateException("Order " + orderId + " was not in state " + from);
    }

    public void updateFillSummary(UUID orderId, int filledQuantity, java.math.BigDecimal averageFillPrice, Instant now) {
        jdbc.sql("UPDATE paper_order SET filled_quantity = :qty, average_fill_price = :price, updated_at = :now WHERE id = :id")
            .param("qty", filledQuantity).param("price", averageFillPrice).param("now", ts(now)).param("id", orderId).update();
    }

    public OrderResult loadResult(UUID orderId, boolean replayed) {
        OrderView order = jdbc.sql("SELECT * FROM paper_order WHERE id = :id").param("id", orderId)
            .query((rs, row) -> toOrderView(rs)).optional()
            .orElseThrow(() -> new NotFoundException("Paper order not found: " + orderId));
        DecisionView decision = jdbc.sql("SELECT * FROM risk_decision WHERE order_id = :id").param("id", orderId)
            .query((rs, row) -> toDecisionView(rs)).optional().orElse(null);
        FillView fill = jdbc.sql("SELECT * FROM paper_fill WHERE order_id = :id ORDER BY fill_sequence DESC LIMIT 1")
            .param("id", orderId)
            .query((rs, row) -> new FillView(rs.getObject("id", UUID.class), rs.getBigDecimal("fill_price"),
                rs.getBigDecimal("gross_amount"), rs.getBigDecimal("fee_amount"), rs.getBigDecimal("slippage_amount"),
                rs.getBigDecimal("net_amount"), rs.getBigDecimal("cost_basis"), rs.getBigDecimal("realized_pnl"),
                instant(rs, "filled_at"))).optional().orElse(null);
        return new OrderResult(order, decision, fill, replayed);
    }

    public static OrderView toOrderView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderView(rs.getObject("id", UUID.class), rs.getObject("instrument_id", Long.class),
            rs.getString("exchange"), rs.getString("symbol"), rs.getString("side"), rs.getInt("quantity"),
            rs.getInt("submitted_quantity"), rs.getInt("filled_quantity"), rs.getString("order_type"),
            rs.getBigDecimal("limit_price"), rs.getString("product_type"), rs.getString("strategy"),
            rs.getObject("candidate_id", Long.class), rs.getBigDecimal("average_fill_price"),
            rs.getBigDecimal("reference_price"), rs.getBigDecimal("stop_loss"), rs.getBigDecimal("target_price"),
            rs.getString("status"), rs.getString("rejection_reason"), instant(rs, "signal_generated_at"),
            instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("correlation_id"));
    }

    public static DecisionView toDecisionView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DecisionView(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
            rs.getString("decision"), parseReasons(rs.getString("reason_codes")), rs.getBigDecimal("risk_budget"),
            rs.getBigDecimal("estimated_risk"), rs.getBigDecimal("available_cash"), rs.getInt("open_positions"),
            rs.getBigDecimal("realized_pnl_today"), rs.getBigDecimal("daily_loss_limit"),
            rs.getString("policy_version"), instant(rs, "evaluated_at"));
    }

    static List<String> parseReasons(String value) {
        String inner = value.replace("[", "").replace("]", "").replace("\"", "").trim();
        return inner.isEmpty() ? List.of() : Arrays.stream(inner.split(",")).map(String::trim).toList();
    }

    public static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record ExistingOrder(UUID id, String requestHash) {}
}
