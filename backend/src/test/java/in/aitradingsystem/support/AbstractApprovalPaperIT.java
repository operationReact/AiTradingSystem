package in.aitradingsystem.support;

import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real persisted policy, catalogue and market evidence for tests that actually settle PAPER orders. */
public abstract class AbstractApprovalPaperIT extends AbstractPostgresIT {
    protected static final Instant NOW = Instant.parse("2026-10-30T04:30:00Z"); // Friday, 10:00 IST
    @MockitoBean Clock approvalClock;
    @Autowired protected JdbcClient approvalJdbc;
    protected UUID executionPolicyId;
    private UUID catalogue;
    private UUID previousCatalogue;

    @BeforeEach
    void seedApprovalEnvironment() {
        when(approvalClock.instant()).thenReturn(NOW);
        when(approvalClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(approvalClock.withZone(org.mockito.ArgumentMatchers.any(java.time.ZoneId.class)))
            .thenAnswer(invocation -> Clock.fixed(NOW, invocation.getArgument(0)));
        executionPolicyId = UUID.randomUUID();
        int version = approvalJdbc.sql("SELECT COALESCE(max(policy_version),0) + 1 FROM execution_policy")
            .query(Integer.class).single();
        approvalJdbc.sql("""
            INSERT INTO execution_policy (id, policy_version, execution_mode, trading_style, approval_mode,
                max_order_notional, max_daily_trades, max_gross_exposure, max_daily_loss,
                active, effective_from, created_by)
            VALUES (:id, :version, 'PAPER', 'ANY', 'AUTO', 100000000, 100000, 100000000, 100000000,
                TRUE, :from, 'P07_WORKFLOW_IT')
            """).param("id", executionPolicyId).param("version", version)
            .param("from", NOW.minusSeconds(86400).atOffset(ZoneOffset.UTC)).update();
        previousCatalogue = approvalJdbc.sql("SELECT active_version FROM instrument_catalogue_state WHERE singleton_id = 1")
            .query(UUID.class).optional().orElse(null);
        catalogue = UUID.randomUUID();
        approvalJdbc.sql("""
            INSERT INTO instrument_import_run (id, status, source_url, started_at, completed_at,
                accepted_count, active_count, catalogue_version)
            VALUES (:id, 'SUCCEEDED', 'approval-workflow-test', now(), now(), 1, 1, :id)
            """).param("id", catalogue).update();
        approvalJdbc.sql("UPDATE instrument_catalogue_state SET active_version = :id WHERE singleton_id = 1")
            .param("id", catalogue).update();
    }

    protected synchronized void market(String symbol, BigDecimal price) {
        Long instrument = approvalJdbc.sql("SELECT instrument_id FROM instrument_master WHERE catalogue_version = :v AND trading_symbol = :s")
            .param("v", catalogue).param("s", symbol).query(Long.class).optional().orElse(null);
        if (instrument == null) {
            instrument = approvalJdbc.sql("""
                INSERT INTO instrument_master (exchange_segment, exchange, symbol_token, trading_symbol,
                    instrument_name, instrument_type, lot_size, tick_size, active, catalogue_version)
                VALUES ('NSE', 'NSE', :token, :s, :s, 'EQ', 1, 0.05, TRUE, :v) RETURNING instrument_id
                """).param("token", UUID.randomUUID().toString().replace("-", "")).param("s", symbol).param("v", catalogue)
                .query(Long.class).single();
        }
        // Refresh the persisted mark when a test deliberately changes price (e.g. BUY then SELL accounting).
        approvalJdbc.sql("DELETE FROM market_candle WHERE instrument_id = :i").param("i", instrument).update();
        approvalJdbc.sql("""
            INSERT INTO market_candle (instrument_id, exchange, trading_symbol, symbol_token, catalogue_version,
                interval, candle_time, open_price, high_price, low_price, close_price, volume)
            SELECT instrument_id, exchange, trading_symbol, symbol_token, catalogue_version,
                'ONE_MINUTE', :at, :price, :price, :price, :price, 10000
              FROM instrument_master WHERE instrument_id = :i
            """).param("i", instrument).param("at", NOW.minusSeconds(10).atOffset(ZoneOffset.UTC))
            .param("price", price).update();
    }

    protected void deleteApprovals(String symbol) {
        approvalJdbc.sql("UPDATE paper_order SET approval_id = NULL WHERE symbol = :s").param("s", symbol).update();
        approvalJdbc.sql("DELETE FROM order_approval WHERE order_id IN (SELECT id FROM paper_order WHERE symbol = :s)")
            .param("s", symbol).update();
    }

    @AfterEach
    void restoreApprovalEnvironment() {
        // Other integration suites rebuild catalogues and risk policies. Remove only this fixture's owned order
        // graph, in FK order, and reverse its ledger movements instead of weakening production constraints.
        String owned = "SELECT id FROM paper_order WHERE instrument_id IN "
            + "(SELECT instrument_id FROM instrument_master WHERE catalogue_version = :v)";
        approvalJdbc.sql("UPDATE paper_account SET cash_balance = cash_balance - COALESCE((SELECT sum(amount) FROM paper_cash_entry WHERE order_id IN (" + owned + ")), 0) WHERE id = 1")
            .param("v", catalogue).update();
        approvalJdbc.sql("DELETE FROM audit_event WHERE entity_id IN (SELECT id::text FROM order_approval WHERE order_id IN (" + owned + ")) OR entity_id IN (SELECT id::text FROM paper_order WHERE id IN (" + owned + "))")
            .param("v", catalogue).update();
        approvalJdbc.sql("UPDATE paper_order SET approval_id = NULL WHERE id IN (" + owned + ")").param("v", catalogue).update();
        approvalJdbc.sql("DELETE FROM order_approval WHERE order_id IN (" + owned + ")").param("v", catalogue).update();
        approvalJdbc.sql("DELETE FROM risk_decision_rule WHERE risk_decision_id IN (SELECT id FROM risk_decision WHERE order_id IN (" + owned + "))")
            .param("v", catalogue).update();
        for (String table : java.util.List.of("risk_decision", "paper_fill", "paper_cash_entry")) {
            approvalJdbc.sql("DELETE FROM " + table + " WHERE order_id IN (" + owned + ")").param("v", catalogue).update();
        }
        approvalJdbc.sql("DELETE FROM paper_position WHERE instrument_id IN (SELECT instrument_id FROM instrument_master WHERE catalogue_version = :v)")
            .param("v", catalogue).update();
        approvalJdbc.sql("DELETE FROM paper_order WHERE id IN (" + owned + ")").param("v", catalogue).update();
        approvalJdbc.sql("DELETE FROM execution_policy WHERE id = :id").param("id", executionPolicyId).update();
        approvalJdbc.sql("UPDATE instrument_catalogue_state SET active_version = :v WHERE singleton_id = 1")
            .param("v", previousCatalogue, java.sql.Types.OTHER).update();
        approvalJdbc.sql("DELETE FROM market_candle WHERE catalogue_version = :v").param("v", catalogue).update();
        approvalJdbc.sql("DELETE FROM instrument_master WHERE catalogue_version = :v").param("v", catalogue).update();
        approvalJdbc.sql("DELETE FROM instrument_import_run WHERE id = :v").param("v", catalogue).update();
    }
}
