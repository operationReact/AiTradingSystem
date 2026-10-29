package in.aitradingsystem.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.risk.RiskContextFactory;
import in.aitradingsystem.risk.RiskPolicyEngine;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ExecutionReadinessServiceIT extends AbstractPostgresIT {
    private static final Instant NOW = Instant.parse("2026-10-29T04:30:00Z"); // 10:00 IST, Thursday
    private static final String SYMBOL = "P7READY";
    private static final int POLICY_VERSION = 730001;

    @Autowired JdbcClient jdbc;
    @Autowired RiskContextFactory contextFactory;
    @Autowired RiskPolicyEngine riskEngine;
    @Autowired ExecutionPolicyService policies;
    @Autowired ApprovalPolicyEvaluator policyEvaluator;
    @Autowired ObjectMapper json;

    private UUID previousCatalogueVersion;
    private UUID catalogueVersion;
    private long instrumentId;
    private UUID orderId;
    private UUID policyId;
    private UUID approvalId;
    private UUID riskDecisionId;

    private ExecutionReadinessService service;

    @BeforeEach
    void setup() {
        service = new ExecutionReadinessService(jdbc, contextFactory, riskEngine, policies, policyEvaluator,
            Clock.fixed(NOW, ZoneOffset.UTC));
        previousCatalogueVersion = jdbc.sql("SELECT active_version FROM instrument_catalogue_state WHERE singleton_id = 1")
            .query(UUID.class).optional().orElse(null);
        jdbc.sql("UPDATE trading_control SET new_orders_halted = FALSE, reason = NULL WHERE id = 1").update();
        seedCatalogue();
        seedExecutionPolicy();
        seedOrderApproval("AUTO_APPROVED", NOW.plusSeconds(300));
        seedCandle(NOW.minusSeconds(30), new BigDecimal("101.25"));
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("UPDATE paper_order SET approval_id = NULL WHERE id = :id").param("id", orderId).update();
        jdbc.sql("DELETE FROM order_approval WHERE id = :id").param("id", approvalId).update();
        jdbc.sql("DELETE FROM risk_decision_rule WHERE risk_decision_id = :id").param("id", riskDecisionId).update();
        jdbc.sql("DELETE FROM risk_decision WHERE id = :id").param("id", riskDecisionId).update();
        jdbc.sql("DELETE FROM paper_order WHERE id = :id").param("id", orderId).update();
        jdbc.sql("DELETE FROM market_candle WHERE instrument_id = :id").param("id", instrumentId).update();
        jdbc.sql("UPDATE instrument_catalogue_state SET active_version = :version, activated_at = now() WHERE singleton_id = 1")
            .param("version", previousCatalogueVersion, java.sql.Types.OTHER).update();
        jdbc.sql("DELETE FROM instrument_master WHERE catalogue_version = :version").param("version", catalogueVersion).update();
        jdbc.sql("DELETE FROM instrument_import_run WHERE id = :version").param("version", catalogueVersion).update();
        jdbc.sql("DELETE FROM execution_policy WHERE id = :id").param("id", policyId).update();
        jdbc.sql("UPDATE trading_control SET new_orders_halted = FALSE, reason = NULL WHERE id = 1").update();
    }

    @Test
    void revalidatesFreshQuoteAccountRiskApprovalPolicyAndSessionImmediatelyBeforeExecution() {
        ExecutionReadinessResult result = service.revalidate(orderId);

        assertThat(result.command().entryPrice()).isEqualByComparingTo("101.25");
        assertThat(result.riskDecision().decisionClass().name()).isEqualTo("APPROVED");
        assertThat(result.executionPolicy().id()).isEqualTo(policyId);
        assertThat(result.policyResult().decision()).isEqualTo(ApprovalEvaluationDecision.AUTO_APPROVED);
    }

    @Test
    void failsClosedWhenApprovalExpired() {
        jdbc.sql("UPDATE order_approval SET expires_at = :expired WHERE id = :id")
            .param("expired", OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC))
            .param("id", approvalId).update();

        assertNotReady("APPROVAL_EXPIRED");
    }

    @Test
    void failsClosedWhenLatestMarketStateIsStale() {
        jdbc.sql("UPDATE market_candle SET candle_time = :stale WHERE instrument_id = :instrumentId")
            .param("stale", OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC))
            .param("instrumentId", instrumentId).update();

        assertNotReady("MARKET_STATE_STALE");
    }

    @Test
    void failsClosedWhenApprovedExecutionPolicyNoLongerResolves() {
        jdbc.sql("UPDATE execution_policy SET active = FALSE WHERE id = :id").param("id", policyId).update();

        assertNotReady("EXECUTION_POLICY_UNAVAILABLE");
    }

    @Test
    void failsClosedWhenEmergencyStopActivatesAfterApproval() {
        jdbc.sql("UPDATE trading_control SET new_orders_halted = TRUE, reason = 'test' WHERE id = 1").update();

        assertNotReady("EMERGENCY_STOP_ACTIVE");
    }

    private void assertNotReady(String code) {
        assertThatThrownBy(() -> service.revalidate(orderId))
            .isInstanceOf(ExecutionNotReadyException.class)
            .satisfies(error -> assertThat(((ExecutionNotReadyException) error).reasonCode()).isEqualTo(code));
    }

    private void seedCatalogue() {
        catalogueVersion = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO instrument_import_run (
                    id, status, source_url, started_at, completed_at, accepted_count, active_count, catalogue_version)
                VALUES (:id, 'SUCCEEDED', 'p07-readiness-test', now(), now(), 1, 1, :id)
                """).param("id", catalogueVersion).update();
        instrumentId = jdbc.sql("""
                INSERT INTO instrument_master (
                    exchange_segment, exchange, symbol_token, trading_symbol, instrument_name, instrument_type,
                    lot_size, tick_size, active, catalogue_version)
                VALUES ('NSE', 'NSE', :token, :symbol, :symbol, 'EQ', 1, 0.05, TRUE, :version)
                RETURNING instrument_id
                """)
            .param("token", "P7-" + Math.abs(catalogueVersion.hashCode()))
            .param("symbol", SYMBOL)
            .param("version", catalogueVersion)
            .query(Long.class).single();
        jdbc.sql("UPDATE instrument_catalogue_state SET active_version = :version, activated_at = now() WHERE singleton_id = 1")
            .param("version", catalogueVersion).update();
    }

    private void seedExecutionPolicy() {
        policyId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO execution_policy (
                    id, policy_version, execution_mode, strategy, trading_style, approval_mode,
                    max_order_notional, max_daily_trades, max_gross_exposure, max_daily_loss,
                    active, effective_from, created_by)
                VALUES (:id, :version, 'PAPER', 'PAPER_MANUAL', 'SWING', 'AUTO',
                        100000, 100, 1000000, 100000, TRUE, now() - interval '1 day', 'P07_PR04_TEST')
                """)
            .param("id", policyId).param("version", POLICY_VERSION).update();
    }

    private void seedOrderApproval(String state, Instant expiresAt) {
        orderId = UUID.randomUUID();
        riskDecisionId = UUID.randomUUID();
        approvalId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO paper_order (
                    id, idempotency_key, request_hash, symbol, side, quantity, reference_price,
                    stop_loss, target_price, signal_generated_at, status, created_at, updated_at,
                    submitted_quantity, filled_quantity, order_type, product_type, instrument_id, exchange, strategy)
                VALUES (:id, :key, :hash, :symbol, 'BUY', 1, 100, 95, 110, :signal, 'READY', :created, :created,
                        1, 0, 'MARKET', 'DELIVERY', :instrumentId, 'NSE', 'PAPER_MANUAL')
                """)
            .param("id", orderId)
            .param("key", "p07-readiness-" + orderId)
            .param("hash", "0".repeat(64))
            .param("symbol", SYMBOL)
            .param("signal", OffsetDateTime.ofInstant(NOW.minusSeconds(30), ZoneOffset.UTC))
            .param("created", OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC))
            .param("instrumentId", instrumentId)
            .update();
        jdbc.sql("""
                INSERT INTO risk_decision (
                    id, order_id, decision, reason_codes, risk_budget, estimated_risk,
                    estimated_notional, available_cash, open_positions, realized_pnl_today,
                    daily_loss_limit, policy_version, evaluated_at, result_class)
                VALUES (:id, :orderId, 'APPROVED', '[]'::jsonb, 100, 5, 100, 100000, 0, 0, 100000,
                        'risk-policy-v1', :evaluated, 'APPROVED')
                """)
            .param("id", riskDecisionId).param("orderId", orderId)
            .param("evaluated", OffsetDateTime.ofInstant(NOW.minusSeconds(30), ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO order_approval (
                    id, order_id, policy_id, policy_version, state, requested_at, expires_at,
                    decided_at, decided_by, row_version, risk_decision_id, request_snapshot)
                VALUES (:id, :orderId, :policyId, :version, :state, :requested, :expires,
                        :decided, 'P07_PR04_TEST', 0, :riskId, '{}'::jsonb)
                """)
            .param("id", approvalId).param("orderId", orderId).param("policyId", policyId)
            .param("version", POLICY_VERSION).param("state", state)
            .param("requested", OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC))
            .param("expires", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
            .param("decided", OffsetDateTime.ofInstant(NOW.minusSeconds(30), ZoneOffset.UTC))
            .param("riskId", riskDecisionId).update();
        jdbc.sql("UPDATE paper_order SET approval_id = :approvalId WHERE id = :orderId")
            .param("approvalId", approvalId).param("orderId", orderId).update();
    }

    private void seedCandle(Instant at, BigDecimal close) {
        jdbc.sql("""
                INSERT INTO market_candle (
                    instrument_id, exchange, trading_symbol, symbol_token, catalogue_version, interval,
                    candle_time, open_price, high_price, low_price, close_price, volume)
                SELECT :instrumentId, 'NSE', :symbol, symbol_token, :version, 'ONE_MINUTE',
                       :time, :close, :close, :close, :close, 1000
                  FROM instrument_master
                 WHERE instrument_id = :instrumentId
                """)
            .param("instrumentId", instrumentId).param("symbol", SYMBOL).param("version", catalogueVersion)
            .param("time", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).param("close", close).update();
    }
}
