package in.aitradingsystem.tradingrooms;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Fail-closed default. It never invents an expiry. A verified producer must be added as a
 * {@code @Primary} bean, which replaces this one.
 */
@Component
public class UnboundCustomerFnoExpiryCatalog implements CustomerFnoExpiryCatalog {

    @Override
    public Listing listing(Instant now) {
        return new Listing(Listing.BLOCKED, null, List.of());
    }
}
