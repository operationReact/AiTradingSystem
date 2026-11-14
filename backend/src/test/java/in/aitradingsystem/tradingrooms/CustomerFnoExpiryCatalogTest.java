package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class CustomerFnoExpiryCatalogTest {
    private static final LocalDate TODAY = LocalDate.parse("2026-11-16");

    @Test
    void unboundCatalogFailsClosedAndNeverInventsAnExpiry() {
        var listing = new UnboundCustomerFnoExpiryCatalog().listing(Instant.parse("2026-11-16T04:00:00Z"));
        assertThat(listing.status()).isEqualTo(CustomerFnoExpiryCatalog.Listing.BLOCKED);
        assertThat(listing.usable()).isFalse();
        assertThat(listing.expiries()).isEmpty();
    }

    @Test
    void onlyVerifiedListingsWithAnObservedTimeAreUsable() {
        assertThat(new CustomerFnoExpiryCatalog.Listing("VERIFIED", null, List.of(TODAY)).usable()).isFalse();
        assertThat(new CustomerFnoExpiryCatalog.Listing("VERIFIED", Instant.EPOCH, List.of(TODAY)).usable()).isTrue();
        assertThat(new CustomerFnoExpiryCatalog.Listing("BLOCKED_SOURCE_NOT_BOUND", Instant.EPOCH, List.of()).usable())
            .isFalse();
    }

    @Test
    void keepsOnlyFutureWindowExpiriesSortedAndDeduped() {
        var listed = List.of(
            LocalDate.parse("2026-11-24"),
            LocalDate.parse("2026-11-10"),
            LocalDate.parse("2026-11-17"),
            LocalDate.parse("2026-11-17"),
            LocalDate.parse("2027-06-01"));
        assertThat(CustomerPaperFnoMarketController.validExpiries(listed, TODAY))
            .containsExactly(LocalDate.parse("2026-11-17"), LocalDate.parse("2026-11-24"));
    }

    @Test
    void today_is_included_and_the_ninety_day_boundary_is_inclusive() {
        var listed = List.of(TODAY, TODAY.plusDays(90), TODAY.plusDays(91));
        assertThat(CustomerPaperFnoMarketController.validExpiries(listed, TODAY))
            .containsExactly(TODAY, TODAY.plusDays(90));
    }

    @Test
    void rejectsUnknownCustomerBeforeAnyExpiryRead() {
        var catalog = mock(CustomerFnoExpiryCatalog.class);
        var controller = new CustomerPaperFnoMarketController(mock(JdbcClient.class),
            mock(CustomerIdentityStore.class), mock(UpstoxAnalyticsSource.class),
            mock(UpstoxFnoV3LiveFeedService.class), mock(MarketSessionCalendar.class), catalog,
            Clock.fixed(Instant.parse("2026-11-16T04:00:00Z"), ZoneOffset.UTC), true, true);
        assertThatThrownBy(() -> controller.expiries(UUID.randomUUID(), null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(catalog);
    }
}
