package in.aitradingsystem.paper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.notification.NotificationService;
import in.aitradingsystem.notification.NotificationSeverity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only accounting reconciliation for PAPER execution state. Differences are persisted as evidence;
 * source orders, fills, positions and cash-ledger rows are never repaired or deleted by this service.
 */
@Service
public class PaperReconciliationService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final NotificationService notifications;

    public PaperReconciliationService(JdbcClient jdbc, ObjectMapper json, Clock clock, NotificationService notifications) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.notifications = notifications;
    }

    @Transactional
    public ReconciliationResult run() {
        UUID runId = UUID.randomUUID();
        Instant startedAt = clock.instant();
        jdbc.sql("INSERT INTO paper_reconciliation_run (id, status, started_at) VALUES (:id, 'RUNNING', :startedAt)")
            .param("id", runId)
            .param("startedAt", ts(startedAt))
            .update();

        List<Difference> differences = new ArrayList<>();
        int checks = 0;
        try {
            checks += reconcileCash(differences);
            checks += reconcileOrderFillQuantities(differences);
            checks += reconcilePositions(differences);
            checks += reconcileSellRealizedPnl(differences);
            checks += reconcileRejectedOrders(differences);
            persistDifferences(runId, differences);

            Instant completedAt = clock.instant();
            String status = differences.isEmpty() ? "CLEAN" : "DIFFERENCES";
            jdbc.sql("""
                    UPDATE paper_reconciliation_run
                       SET status = :status, completed_at = :completedAt,
                           checks_performed = :checks, difference_count = :differenceCount
                     WHERE id = :id AND status = 'RUNNING'
                    """)
                .param("status", status)
                .param("completedAt", ts(completedAt))
                .param("checks", checks)
                .param("differenceCount", differences.size())
                .param("id", runId)
                .update();
            if (!differences.isEmpty()) notifications.raise("RECONCILIATION_DIFFERENCE", NotificationSeverity.CRITICAL,
                "PAPER reconciliation found differences", "Review the persisted reconciliation evidence. Source accounting has not been repaired.",
                "PAPER_RECONCILIATION", runId.toString(), "RECONCILIATION_DIFFERENCE:ACCOUNT_1");
            return new ReconciliationResult(runId, status, checks, differences.size(), List.copyOf(differences));
        } catch (RuntimeException ex) {
            jdbc.sql("""
                    UPDATE paper_reconciliation_run
                       SET status = 'FAILED', completed_at = :completedAt,
                           checks_performed = :checks, difference_count = :differenceCount,
                           error_code = 'RECONCILIATION_FAILED', error_message = :message
                     WHERE id = :id AND status = 'RUNNING'
                    """)
                .param("completedAt", ts(clock.instant()))
                .param("checks", checks)
                .param("differenceCount", differences.size())
                .param("message", bounded(ex.getMessage(), 500))
                .param("id", runId)
                .update();
            throw ex;
        }
    }

    private int reconcileCash(List<Difference> differences) {
        CashState state = jdbc.sql("""
                SELECT a.starting_capital,
                       a.cash_balance,
                       COALESCE(SUM(CASE WHEN e.entry_type <> 'CAPITAL_SEED' THEN e.amount ELSE 0 END), 0) ledger_delta
                  FROM paper_account a
                  LEFT JOIN paper_cash_entry e ON TRUE
                 WHERE a.id = 1
                 GROUP BY a.starting_capital, a.cash_balance
                """)
            .query((rs, row) -> new CashState(
                rs.getBigDecimal("starting_capital"), rs.getBigDecimal("cash_balance"),
                rs.getBigDecimal("ledger_delta")))
            .single();
        BigDecimal expected = money(state.startingCapital().add(state.ledgerDelta()));
        BigDecimal actual = money(state.cashBalance());
        if (expected.compareTo(actual) != 0) {
            differences.add(diff("PAPER_ACCOUNT", "1", "CASH_LEDGER_MISMATCH",
                Map.of("cash", expected), Map.of("cash", actual), "CRITICAL"));
        }
        return 1;
    }

    private int reconcileOrderFillQuantities(List<Difference> differences) {
        List<OrderFillState> rows = jdbc.sql("""
                SELECT o.id, COALESCE(o.filled_quantity, 0) recorded_filled,
                       COALESCE(SUM(f.quantity), 0) fill_total
                  FROM paper_order o
                  LEFT JOIN paper_fill f ON f.order_id = o.id
                 GROUP BY o.id, o.filled_quantity
                """)
            .query((rs, row) -> new OrderFillState(
                rs.getObject("id", UUID.class), rs.getInt("recorded_filled"), rs.getInt("fill_total")))
            .list();
        for (OrderFillState row : rows) {
            if (row.recordedFilled() != row.fillTotal()) {
                differences.add(diff("PAPER_ORDER", row.orderId().toString(), "ORDER_FILL_QUANTITY_MISMATCH",
                    Map.of("filledQuantity", row.fillTotal()), Map.of("filledQuantity", row.recordedFilled()), "ERROR"));
            }
        }
        return rows.size();
    }

    private int reconcilePositions(List<Difference> differences) {
        List<ExpectedPosition> expected = jdbc.sql("""
                SELECT COALESCE(o.instrument_id::text, 'SYMBOL:' || o.symbol) identity,
                       o.product_type,
                       MIN(o.symbol) symbol,
                       SUM(CASE WHEN f.side = 'BUY' THEN f.quantity ELSE -f.quantity END) expected_quantity,
                       SUM(CASE WHEN f.side = 'BUY' THEN f.cost_basis ELSE -f.cost_basis END) expected_cost_basis
                  FROM paper_fill f
                  JOIN paper_order o ON o.id = f.order_id
                 GROUP BY COALESCE(o.instrument_id::text, 'SYMBOL:' || o.symbol), o.product_type
                HAVING SUM(CASE WHEN f.side = 'BUY' THEN f.quantity ELSE -f.quantity END) <> 0
                """)
            .query((rs, row) -> new ExpectedPosition(
                rs.getString("identity"), rs.getString("product_type"), rs.getString("symbol"),
                rs.getInt("expected_quantity"), rs.getBigDecimal("expected_cost_basis")))
            .list();

        int checks = expected.size();
        for (ExpectedPosition row : expected) {
            ActualPosition actual = loadPosition(row.identity(), row.productType(), row.symbol());
            int actualQty = actual == null ? 0 : actual.quantity();
            BigDecimal actualCost = actual == null ? BigDecimal.ZERO.setScale(2) : money(actual.costBasis());
            BigDecimal expectedCost = money(row.expectedCostBasis());
            if (row.expectedQuantity() != actualQty) {
                differences.add(diff("PAPER_POSITION", row.identity() + ":" + row.productType(),
                    "POSITION_QUANTITY_MISMATCH",
                    Map.of("quantity", row.expectedQuantity()), Map.of("quantity", actualQty), "CRITICAL"));
            }
            if (expectedCost.compareTo(actualCost) != 0) {
                differences.add(diff("PAPER_POSITION", row.identity() + ":" + row.productType(),
                    "POSITION_COST_BASIS_MISMATCH",
                    Map.of("costBasis", expectedCost), Map.of("costBasis", actualCost), "CRITICAL"));
            }
        }

        List<ActualOnlyPosition> actualPositions = jdbc.sql("""
                SELECT COALESCE(instrument_id::text, 'SYMBOL:' || symbol) identity,
                       product_type, symbol, quantity, cost_basis
                  FROM paper_position
                """)
            .query((rs, row) -> new ActualOnlyPosition(
                rs.getString("identity"), rs.getString("product_type"), rs.getString("symbol"),
                rs.getInt("quantity"), rs.getBigDecimal("cost_basis")))
            .list();
        for (ActualOnlyPosition actual : actualPositions) {
            boolean represented = expected.stream().anyMatch(e -> e.identity().equals(actual.identity())
                && e.productType().equals(actual.productType()));
            if (!represented) {
                checks++;
                differences.add(diff("PAPER_POSITION", actual.identity() + ":" + actual.productType(),
                    "POSITION_WITHOUT_FILL_HISTORY", Map.of("quantity", 0, "costBasis", BigDecimal.ZERO),
                    Map.of("quantity", actual.quantity(), "costBasis", money(actual.costBasis())), "ERROR"));
            }
        }
        return checks;
    }

    private ActualPosition loadPosition(String identity, String productType, String symbol) {
        if (identity.startsWith("SYMBOL:")) {
            return jdbc.sql("""
                    SELECT quantity, cost_basis FROM paper_position
                     WHERE instrument_id IS NULL AND symbol = :symbol AND product_type = :productType
                    """)
                .param("symbol", symbol).param("productType", productType)
                .query((rs, row) -> new ActualPosition(rs.getInt("quantity"), rs.getBigDecimal("cost_basis")))
                .optional().orElse(null);
        }
        return jdbc.sql("""
                SELECT quantity, cost_basis FROM paper_position
                 WHERE instrument_id = :instrumentId AND product_type = :productType
                """)
            .param("instrumentId", Long.parseLong(identity)).param("productType", productType)
            .query((rs, row) -> new ActualPosition(rs.getInt("quantity"), rs.getBigDecimal("cost_basis")))
            .optional().orElse(null);
    }

    private int reconcileSellRealizedPnl(List<Difference> differences) {
        List<SellPnlState> sells = jdbc.sql("""
                SELECT f.id, f.gross_amount, f.fee_amount, f.cost_basis, f.realized_pnl
                  FROM paper_fill f
                 WHERE f.side = 'SELL'
                """)
            .query((rs, row) -> new SellPnlState(
                rs.getObject("id", UUID.class), rs.getBigDecimal("gross_amount"), rs.getBigDecimal("fee_amount"),
                rs.getBigDecimal("cost_basis"), rs.getBigDecimal("realized_pnl")))
            .list();
        for (SellPnlState sell : sells) {
            BigDecimal expected = money(sell.gross().subtract(sell.fee()).subtract(sell.costBasis()));
            BigDecimal actual = money(sell.realizedPnl());
            if (expected.compareTo(actual) != 0) {
                differences.add(diff("PAPER_FILL", sell.id().toString(), "REALIZED_PNL_MISMATCH",
                    Map.of("realizedPnl", expected), Map.of("realizedPnl", actual), "ERROR"));
            }
        }
        return sells.size();
    }

    private int reconcileRejectedOrders(List<Difference> differences) {
        List<RejectedState> rejected = jdbc.sql("""
                SELECT o.id,
                       (SELECT COUNT(*) FROM paper_fill f WHERE f.order_id = o.id) fill_count,
                       (SELECT COUNT(*) FROM paper_cash_entry e WHERE e.order_id = o.id) cash_count
                  FROM paper_order o
                 WHERE o.status = 'REJECTED'
                """)
            .query((rs, row) -> new RejectedState(
                rs.getObject("id", UUID.class), rs.getInt("fill_count"), rs.getInt("cash_count")))
            .list();
        for (RejectedState row : rejected) {
            if (row.fillCount() > 0 || row.cashCount() > 0) {
                differences.add(diff("PAPER_ORDER", row.id().toString(), "REJECTED_ORDER_HAS_SETTLEMENT",
                    Map.of("fillCount", 0, "cashEntryCount", 0),
                    Map.of("fillCount", row.fillCount(), "cashEntryCount", row.cashCount()), "CRITICAL"));
            }
        }
        return rejected.size();
    }

    private void persistDifferences(UUID runId, List<Difference> differences) {
        for (Difference d : differences) {
            jdbc.sql("""
                    INSERT INTO paper_reconciliation_difference (
                        run_id, entity_type, entity_id, difference_code, expected, actual, severity)
                    VALUES (:runId, :entityType, :entityId, :code,
                        CAST(:expected AS jsonb), CAST(:actual AS jsonb), :severity)
                    """)
                .param("runId", runId)
                .param("entityType", d.entityType())
                .param("entityId", d.entityId())
                .param("code", d.code())
                .param("expected", toJson(d.expected()))
                .param("actual", toJson(d.actual()))
                .param("severity", d.severity())
                .update();
        }
    }

    private Difference diff(String entityType, String entityId, String code,
                            Map<String, ?> expected, Map<String, ?> actual, String severity) {
        return new Difference(entityType, entityId, code,
            new LinkedHashMap<>(expected), new LinkedHashMap<>(actual), severity);
    }

    private String toJson(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize reconciliation evidence", e);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String bounded(String value, int max) {
        if (value == null) return null;
        String clean = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private record CashState(BigDecimal startingCapital, BigDecimal cashBalance, BigDecimal ledgerDelta) {}
    private record OrderFillState(UUID orderId, int recordedFilled, int fillTotal) {}
    private record ExpectedPosition(String identity, String productType, String symbol,
                                    int expectedQuantity, BigDecimal expectedCostBasis) {}
    private record ActualPosition(int quantity, BigDecimal costBasis) {}
    private record ActualOnlyPosition(String identity, String productType, String symbol,
                                      int quantity, BigDecimal costBasis) {}
    private record SellPnlState(UUID id, BigDecimal gross, BigDecimal fee,
                                BigDecimal costBasis, BigDecimal realizedPnl) {}
    private record RejectedState(UUID id, int fillCount, int cashCount) {}

    public record Difference(String entityType, String entityId, String code,
                             Map<String, ?> expected, Map<String, ?> actual, String severity) {}
    public record ReconciliationResult(UUID runId, String status, int checksPerformed,
                                       int differenceCount, List<Difference> differences) {}
}
