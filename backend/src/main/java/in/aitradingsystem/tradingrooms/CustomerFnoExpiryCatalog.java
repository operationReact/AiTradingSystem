package in.aitradingsystem.tradingrooms;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Port for server-verified, currently listed NIFTY option expiries.
 *
 * <p>Owned by the source producer (Upstox option-contract discovery). Until that producer binds an
 * implementation, {@link UnboundCustomerFnoExpiryCatalog} is the only bean and fails closed. Any
 * implementation must return {@code VERIFIED} only from a real, bounded, authenticated provider read.
 */
public interface CustomerFnoExpiryCatalog {

    Listing listing(Instant now);

    /** A listing is usable only when {@code status} is {@link #VERIFIED} and {@code observedAt} is set. */
    record Listing(String status, Instant observedAt, List<LocalDate> expiries) {
        public static final String VERIFIED = "VERIFIED";
        public static final String BLOCKED = "BLOCKED_SOURCE_NOT_BOUND";

        public Listing {
            expiries = expiries == null ? List.of() : List.copyOf(expiries);
        }

        public boolean usable() {
            return VERIFIED.equals(status) && observedAt != null;
        }
    }
}
