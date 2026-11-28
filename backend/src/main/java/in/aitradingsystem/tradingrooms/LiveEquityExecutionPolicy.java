package in.aitradingsystem.tradingrooms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deployment-owned master switch for LIVE intraday cash-equity dispatch.
 * Defaults to {@code false}: a deployment that sets nothing cannot claim an
 * order or send a broker request.
 *
 * <p>This flag is not authorization by itself. Every order still needs the
 * per-order consent, a fresh preflight, the broker hosting attestation (an
 * operator record in {@code live_equity_hosting_attestation}), the account risk
 * policy, and the durable claim. Broker hosting evidence is read from the
 * database, never from a property, so it can be revoked without a redeploy.
 */
@Component
public record LiveEquityExecutionPolicy(
        @Value("${minttick.live-equity.dispatch-enabled:false}") boolean dispatchEnabled) {

    /** Fail-closed default used whenever no explicit policy is supplied. */
    public static final LiveEquityExecutionPolicy DISABLED = new LiveEquityExecutionPolicy(false);
}
