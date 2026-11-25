package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UpstoxFnoPlusMultiSocketPoolTest {
    private static final Instant NOW = Instant.parse("2026-11-23T08:10:00Z");
    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);
    private final UpstoxFnoV3LiveFeedService primary =
        mock(UpstoxFnoV3LiveFeedService.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);
    private final List<UpstoxFnoV3LiveFeedService> extra = new ArrayList<>();
    private final List<AtomicReference<UpstoxFnoV3LiveFeedService.Status>> shardStates =
        new ArrayList<>();

    static UpstoxFnoV3LiveFeedService.Status status(String state, int n) {
        return new UpstoxFnoV3LiveFeedService.Status(
            state,n,0,0,0,null,null,0,false,false,false,false,0,null);
    }
    static UpstoxFnoV3LiveFeedService.StreamingCounts coverage(String state) {
        return new UpstoxFnoV3LiveFeedService.StreamingCounts(
            state,0,0,0,0,0,0,0,NOW,false);
    }
    private UpstoxFnoPlusMultiSocketPool pool(boolean rights) {
        extra.clear();
        shardStates.clear();
        for (int i=0;i<4;i++) {
            var s = mock(UpstoxFnoV3LiveFeedService.class);
            var ref = new AtomicReference<>(status("STOPPED",0));
            when(s.status()).thenAnswer(x->ref.get());
            when(s.streamingCoverage()).thenAnswer(x->coverage(ref.get().state()));
            doAnswer(x->{ ref.set(status("CONNECTING",
                ((List<?>)x.getArgument(0)).size()));return ref.get(); })
                .when(s).start(anyList());
            extra.add(s);
            shardStates.add(ref);
        }
        when(primary.status()).thenReturn(status("CONNECTED",64));
        when(primary.localStreamingCoverage()).thenReturn(coverage("CONNECTED"));
        when(source.enabled()).thenReturn(true);
        when(calendar.isInsideFnoSession(NOW)).thenReturn(true);
        return new UpstoxFnoPlusMultiSocketPool(
            source, primary, calendar, Clock.fixed(NOW,ZoneOffset.UTC),
            rights,extra);
    }
    @Test
    void upToFourAdditionalSocketsAndPrimaryRemainSeparateAndNonTrading() {
        var pool=pool(true);
        for(int i=0;i<4;i++) {
            pool.subscribeVerified(List.of("NSE_FO|"+(61001+i)));
        }
        var status=pool.status();
        assertThat(status.maximumConnectionsPerUser()).isEqualTo(5);
        assertThat(status.locallyManagedConnections()).isEqualTo(5);
        assertThat(status.locallyTrackedSubscriptions()).isEqualTo(68);
        assertThat(status.liveOrdersEnabled()).isFalse();
        for(var shard:extra) verify(shard, times(1)).start(anyList());
        verify(primary,never()).start(anyList());
        verify(primary,never()).stop();
        assertThatThrownBy(()->pool.subscribeVerified(List.of("NSE_FO|69999")))
            .hasMessage("UPSTOX_PLUS_NO_SAFE_SHARD_AVAILABLE");
    }
    @Test
    void duplicateContractCannotBeSubscribedOnTwoSockets() {
        var pool=pool(true);
        pool.subscribeVerified(List.of("NSE_FO|61001"));
        assertThatThrownBy(()->pool.subscribeVerified(List.of("NSE_FO|61001")))
            .hasMessage("UPSTOX_PLUS_DUPLICATE_SUBSCRIPTION");
        verify(extra.get(1),never()).start(anyList());
    }
    @Test
    void sourceRightsAndRealMarketSessionAreMandatory() {
        var denied=pool(false);
        assertThatThrownBy(()->denied.subscribeVerified(List.of("NSE_FO|61001")))
            .hasMessage("UPSTOX_PLUS_RIGHTS_OR_SESSION_UNVERIFIED");
        verify(extra.get(0),never()).start(anyList());
        var closed=pool(true);
        when(calendar.isInsideFnoSession(NOW)).thenReturn(false);
        assertThatThrownBy(()->closed.subscribeVerified(List.of("NSE_FO|61001")))
            .hasMessage("UPSTOX_PLUS_RIGHTS_OR_SESSION_UNVERIFIED");
    }
    @Test
    void localGlobalFullBudgetIsNotFiveTimesFifteenHundred() {
        var pool=pool(true);
        when(primary.status()).thenReturn(status("CONNECTED",1499));
        assertThatThrownBy(()->pool.subscribeVerified(List.of(
            "NSE_FO|61001","NSE_FO|61002")))
            .hasMessage("UPSTOX_PLUS_LOCAL_FULL_LIMIT");
        verify(extra.get(0),never()).start(anyList());
    }
    @Test
    void marketCloseStopsOnlySupplementalSocketsNeverPrimary() {
        var pool=pool(true);
        pool.subscribeVerified(List.of("NSE_FO|61001"));
        when(calendar.isInsideFnoSession(NOW)).thenReturn(false);
        pool.stopAfterSession();
        verify(extra.get(0)).stop();
        verify(primary,never()).stop();
        assertThat(pool.eligibleSupplementaryQuote("NSE_FO|61001")).isEmpty();
        pool.shutdown();
        for(var shard:extra)verify(shard).shutdown();
        verify(primary,never()).shutdown();
    }
    @Test
    void invalidKeyDoesNotAllocateAnAccountConnectionSlot() {
        var pool=pool(true);
        assertThatThrownBy(()->pool.subscribeVerified(List.of(
            "NSE_EQ|INE002A01018")))
            .hasMessage("UPSTOX_PLUS_INVALID_LISTED_KEYS");
        assertThatThrownBy(()->pool.subscribeVerified(List.of(
            "NSE_FO|61001","NSE_FO|61001")))
            .hasMessage("UPSTOX_PLUS_INVALID_LISTED_KEYS");
        verify(extra.get(0),never()).start(anyList());
    }
}
