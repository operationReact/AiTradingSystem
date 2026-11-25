package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;

class UpstoxCustomerFnoExpiryCatalogTest {
    private static final Instant NOW=Instant.parse("2026-11-15T02:00:00Z");
    private final UpstoxAnalyticsSource source=mock(UpstoxAnalyticsSource.class);

    @Test
    void allProviderReadsRequireVerifiedCustomerDisplayPermission() {
        when(source.enabled()).thenReturn(true);
        var catalog=new UpstoxCustomerFnoExpiryCatalog(source,false);
        var blocked=catalog.listing(NOW);
        assertThat(blocked.usable()).isFalse();
        assertThat(blocked.expiries()).isEmpty();
        assertThat(blocked.observedAt()).isNull();
        assertThat(blocked.status()).isEqualTo("BLOCKED_NO_PERMITTED_SOURCE");
        verify(source,never()).currentNiftyOptionExpiries(any());
    }

    @Test
    void contractDiscoveryPermissionListsExpiriesWithoutDisplayRights() {
        when(source.enabled()).thenReturn(true);
        var date=LocalDate.parse("2026-11-24");
        when(source.currentNiftyOptionExpiries(NOW)).thenReturn(List.of(date));
        var catalog=new UpstoxCustomerFnoExpiryCatalog(source,false,true);
        var listing=catalog.listing(NOW);
        assertThat(listing.usable()).isTrue();
        assertThat(listing.expiries()).containsExactly(date);
        verify(source,times(1)).currentNiftyOptionExpiries(NOW);
    }

    @Test
    void neitherPermissionKeepsExpiryListingBlocked() {
        when(source.enabled()).thenReturn(true);
        var catalog=new UpstoxCustomerFnoExpiryCatalog(source,false,false);
        assertThat(catalog.listing(NOW).usable()).isFalse();
        verify(source,never()).currentNiftyOptionExpiries(any());
    }

    @Test
    void disabledSourceFailsClosedWithoutAnyOutgoingRead() {
        when(source.enabled()).thenReturn(false);
        var catalog=new UpstoxCustomerFnoExpiryCatalog(source,true);
        assertThat(catalog.listing(NOW).usable()).isFalse();
        verify(source,never()).currentNiftyOptionExpiries(any());
    }

    @Test
    void cachesOnlyShortLivedRealListedDatesAndRefreshesAfterTtl() {
        when(source.enabled()).thenReturn(true);
        var date=LocalDate.parse("2026-11-17");
        when(source.currentNiftyOptionExpiries(NOW)).thenReturn(List.of(date));
        var catalog=new UpstoxCustomerFnoExpiryCatalog(source,true);
        var first=catalog.listing(NOW);
        assertThat(first.usable()).isTrue();
        assertThat(first.status()).isEqualTo(CustomerFnoExpiryCatalog.Listing.VERIFIED);
        assertThat(first.expiries()).containsExactly(date);
        assertThat(first.observedAt()).isEqualTo(NOW);
        assertThat(catalog.listing(NOW.plusSeconds(59))).isSameAs(first);
        verify(source,times(1)).currentNiftyOptionExpiries(NOW);
        when(source.currentNiftyOptionExpiries(NOW.plusSeconds(61)))
            .thenReturn(List.of(LocalDate.parse("2026-11-24")));
        var refreshed=catalog.listing(NOW.plusSeconds(61));
        assertThat(refreshed.expiries()).containsExactly(LocalDate.parse("2026-11-24"));
        verify(source,times(1)).currentNiftyOptionExpiries(NOW.plusSeconds(61));
    }

    @Test
    void sourceFailureInvalidatesPreviousVerifiedDatesAndCoolsDown() {
        when(source.enabled()).thenReturn(true);
        when(source.currentNiftyOptionExpiries(NOW))
            .thenReturn(List.of(LocalDate.parse("2026-11-17")));
        when(source.currentNiftyOptionExpiries(NOW.plusSeconds(61)))
            .thenThrow(new IllegalStateException("provider bearer secret token"));
        var catalog=new UpstoxCustomerFnoExpiryCatalog(source,true);
        assertThat(catalog.listing(NOW).usable()).isTrue();
        var blocked=catalog.listing(NOW.plusSeconds(61));
        assertThat(blocked.usable()).isFalse();
        assertThat(blocked.status()).isEqualTo("BLOCKED_SOURCE_UNAVAILABLE");
        assertThat(blocked.expiries()).isEmpty();
        assertThat(blocked.observedAt()).isNull();
        assertThat(blocked.toString()).doesNotContain("secret","token");
        assertThat(catalog.listing(NOW.plusSeconds(80))).isSameAs(blocked);
        verify(source,times(2)).currentNiftyOptionExpiries(any());
    }

    @Test
    void bindingIsPreferredOverUnboundDefaultButDoesNotSelfAuthorize() {
        assertThat(UpstoxCustomerFnoExpiryCatalog.class.isAnnotationPresent(Primary.class))
            .isTrue();
    }
}
