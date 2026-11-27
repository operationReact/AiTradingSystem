package in.aitradingsystem.tradingrooms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deployment-owned execution gates for LIVE intraday cash-equity orders. Both
 * default to {@code false}, so a deployment that sets nothing cannot dispatch
 * a broker order and cannot present an order route as executable.
 *
 * <p>These flags are NOT authorization by themselves. Every order still needs
 * per-order customer consent, a fresh authoritative preflight, the durable
 * claim, and the account-wide risk policy. Turning either flag on is a separate
 * production decision and is not done by this code change.
 *
 * @param dispatchEnabled         master switch: when false, no claim is made and
 *                                no broker request can be sent
 * @param angelOneHostingAttested operator attestation that this deployment's
 *                                egress IP is registered with Angel One. Angel
 *                                One exposes no verifiable static-IP API here,
 *                                so this must be attested explicitly.
 */
@Component
public record LiveEquityExecutionPolicy(
        @Value("${minttick.live-equity.dispatch-enabled:false}") boolean dispatchEnabled,
        @Value("${minttick.live-equity.angel-one-hosting-attested:false}") boolean angelOneHostingAttested) {

    /** Fail-closed default used whenever no explicit policy is supplied. */
    public static final LiveEquityExecutionPolicy DISABLED =
        new LiveEquityExecutionPolicy(false, false);
}
