package in.aitradingsystem.tradingrooms;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operator-only controls for LIVE intraday cash-equity dispatch. Every method
 * requires an operator reference (who is acting) and writes an audit row. None
 * of them places or sends an order. Customer data is never returned.
 */
@Service
public class LiveEquityOperatorService {
    static final Duration MAX_ATTESTATION = Duration.ofDays(90);

    private final JdbcClient jdbc;

    public LiveEquityOperatorService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record RiskPolicyInput(long maxAccountIntradayBuyInr, long maxOrderValueInr,
            int maxOrderQuantity, long dailyRealizedLossLimitInr) {}

    public record Attestation(UUID id, String broker, Instant attestedAt, Instant expiresAt) {}

    /** Records that this deployment's egress is registered with the broker. */
    @Transactional
    public Attestation attestHosting(String broker, String egressIp, String operatorReference,
            Duration validFor) {
        requireOperatorReference(operatorReference);
        if (!"ANGEL_ONE".equals(broker) && !"UPSTOX".equals(broker)) {
            throw bad("Unsupported broker");
        }
        if (egressIp == null || !egressIp.matches("[0-9a-fA-F.:]{7,45}")) {
            throw bad("Egress IP is not a valid address");
        }
        if (validFor == null || validFor.isNegative() || validFor.isZero()
                || validFor.compareTo(MAX_ATTESTATION) > 0) {
            throw bad("Attestation validity must be between 1 second and 90 days");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO live_equity_hosting_attestation
                  (id, broker, egress_ip, operator_reference, attested_at, expires_at)
                VALUES (:id, :broker, :ip, :ref, :at, :exp)
                """)
            .param("id", id).param("broker", broker).param("ip", egressIp)
            .param("ref", operatorReference.trim())
            .param("at", java.sql.Timestamp.from(now))
            .param("exp", java.sql.Timestamp.from(now.plus(validFor)))
            .update();
        return new Attestation(id, broker, now, now.plus(validFor));
    }

    /** Revokes an attestation immediately; readiness then fails closed again. */
    @Transactional
    public void revokeHosting(UUID attestationId, String operatorReference) {
        requireOperatorReference(operatorReference);
        int updated = jdbc.sql("""
                UPDATE live_equity_hosting_attestation
                   SET revoked_at = NOW(), revoked_by_reference = :ref
                 WHERE id = :id AND revoked_at IS NULL
                """)
            .param("id", attestationId).param("ref", operatorReference.trim()).update();
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No active attestation with that id");
        }
    }

    /** Creates or replaces the account-wide risk policy for one customer. */
    @Transactional
    public void setRiskPolicy(UUID customerId, RiskPolicyInput policy, String operatorReference) {
        requireOperatorReference(operatorReference);
        if (customerId == null || policy == null) {
            throw bad("Customer and policy are required");
        }
        if (policy.maxAccountIntradayBuyInr() < 1 || policy.maxAccountIntradayBuyInr() > 10_000_000L
                || policy.maxOrderValueInr() < 1 || policy.maxOrderValueInr() > 10_000_000L
                || policy.maxOrderQuantity() < 1 || policy.maxOrderQuantity() > 10_000
                || policy.dailyRealizedLossLimitInr() < 0
                || policy.dailyRealizedLossLimitInr() > 10_000_000L) {
            throw bad("Risk policy value outside the permitted range");
        }
        boolean customerExists = jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM app_user WHERE id = :id AND role = 'CUSTOMER')")
            .param("id", customerId).query(Boolean.class).single();
        if (!customerExists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }
        jdbc.sql("""
                INSERT INTO customer_live_equity_risk_policy
                  (user_id, max_account_intraday_buy_inr, max_order_value_inr,
                   max_order_quantity, daily_realized_loss_limit_inr, operator_reference, updated_at)
                VALUES (:user, :buy, :value, :qty, :loss, :ref, NOW())
                ON CONFLICT (user_id) DO UPDATE
                   SET max_account_intraday_buy_inr = EXCLUDED.max_account_intraday_buy_inr,
                       max_order_value_inr = EXCLUDED.max_order_value_inr,
                       max_order_quantity = EXCLUDED.max_order_quantity,
                       daily_realized_loss_limit_inr = EXCLUDED.daily_realized_loss_limit_inr,
                       operator_reference = EXCLUDED.operator_reference,
                       updated_at = NOW()
                """)
            .param("user", customerId)
            .param("buy", policy.maxAccountIntradayBuyInr())
            .param("value", policy.maxOrderValueInr())
            .param("qty", policy.maxOrderQuantity())
            .param("loss", policy.dailyRealizedLossLimitInr())
            .param("ref", operatorReference.trim())
            .update();
    }

    /**
     * Operator release of a reserved order whose broker outcome is unknown, and
     * for which no broker order exists. This is an attested release: the operator
     * must supply the evidence reference (for example an exported broker order
     * book showing no order with the correlation tag). Orders with a broker order
     * id, acknowledged or filled orders, and terminal orders are never released here.
     */
    @Transactional
    public void resolveNotPlaced(UUID orderId, String operatorReference, String evidenceReference) {
        requireOperatorReference(operatorReference);
        if (evidenceReference == null || evidenceReference.isBlank() || evidenceReference.length() > 200) {
            throw bad("Evidence reference is required");
        }
        var current = jdbc.sql("""
                SELECT state, broker_order_id FROM customer_live_equity_order
                WHERE id = :id FOR UPDATE
                """)
            .param("id", orderId)
            .query((rs, n) -> new String[] {rs.getString("state"), rs.getString("broker_order_id")})
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        String state = current[0];
        if (!("SUBMITTING".equals(state) || "UNKNOWN".equals(state)) || current[1] != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only an unknown order with no broker order id can be released");
        }
        jdbc.sql("""
                UPDATE customer_live_equity_order
                   SET state = 'REJECTED', outcome_code = 'OPERATOR_CONFIRMED_NOT_PLACED', updated_at = NOW()
                 WHERE id = :id AND state = :state AND broker_order_id IS NULL
                """)
            .param("id", orderId).param("state", state).update();
        jdbc.sql("""
                INSERT INTO live_equity_order_operator_resolution
                  (order_id, resolution, previous_state, operator_reference, evidence_reference)
                VALUES (:id, 'CONFIRMED_NOT_PLACED', :state, :ref, :evidence)
                """)
            .param("id", orderId).param("state", state)
            .param("ref", operatorReference.trim()).param("evidence", evidenceReference.trim())
            .update();
    }

    private static void requireOperatorReference(String reference) {
        if (reference == null || reference.isBlank() || reference.length() > 200) {
            throw bad("Operator reference is required");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
