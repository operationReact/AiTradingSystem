package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import in.aitradingsystem.tradingrooms.FnoFuturesContractRegistry;
import in.aitradingsystem.tradingrooms.FnoFuturesContractRegistry.NearFar;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import in.aitradingsystem.provider.upstox.UpstoxFuturesInstrumentMaster.FuturesContract;

class UpstoxSpotLegSubscriptionTest {
    private static final String SPOT = "NSE_EQ|INE002A01018";
    private static final String FUT = "NSE_FO|61471";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T04:00:00Z"), ZoneOffset.UTC);

    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);
    private final ScheduledExecutorService reconnects = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void shutdown() {
        reconnects.shutdownNow();
    }

    private UpstoxFnoV3LiveFeedService service() {
        when(source.enabled()).thenReturn(true);
        return new UpstoxFnoV3LiveFeedService(source, CLOCK,
                (uri, listener) -> new CompletableFuture<>(), reconnects);
    }

    @Test
    void spotKeysAreRejectedByTheDerivativeOnlyStartPath() {
        var s = service();
        assertThatThrownBy(() -> s.start(List.of(SPOT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void spotKeyMustBeIsinShapedAndDistinctAndWithinCap() {
        var s = service();
        assertThatThrownBy(() -> s.start(List.of(FUT), List.of("NSE_EQ|INFY")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.start(List.of(FUT), List.of(FUT)))
                .isInstanceOf(IllegalArgumentException.class);
        var many = new java.util.ArrayList<String>();
        for (int i = 0; i < 64; i++) many.add("NSE_FO|" + (1000 + i));
        assertThatThrownBy(() -> s.start(many, List.of(SPOT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validSpotStartSubscribesOnceAndQuoteStaysEmptyWithoutATick() {
        var s = service();
        var status = s.start(List.of(FUT), List.of(SPOT));
        assertThat(status.subscriptions()).isEqualTo(2);
        assertThat(s.eligiblePaperQuote(SPOT)).isEmpty();
        assertThat(s.eligiblePaperQuote("NSE_EQ|INFY")).isEmpty();
        s.stop();
        // After stop, spot publication is cleared: no quote for a previously subscribed spot key.
        assertThat(s.eligiblePaperQuote(SPOT)).isEmpty();
    }

    @Test
    void equitySpotQuotesAreNeverReturnedToPaperOrOrderGates() {
        var s = service();
        s.start(List.of(FUT), List.of(SPOT));
        assertThat(s.eligiblePaperQuote(SPOT)).isEmpty();
        assertThat(s.eligibleDisplayQuote(SPOT)).isEmpty();
        assertThat(s.eligibleResearchSpotQuote(SPOT)).isEmpty();
    }

    @Test
    void researchSpotAccessorRejectsUnsubscribedAndDerivativeKeys() {
        var s = service();
        assertThat(s.eligibleResearchSpotQuote(SPOT)).isEmpty();
        s.start(List.of(FUT));
        assertThat(s.eligibleResearchSpotQuote(SPOT)).isEmpty();
        assertThat(s.eligibleResearchSpotQuote(FUT)).isEmpty();
    }

    @Test
    void controllerSpotModeSubscribesOnlyTheVerifiedNearFutureAndSpot() {
        var registry = mock(FnoFuturesContractRegistry.class);
        var near = new FuturesContract(FUT, SPOT, "RELIANCE", "RELIANCE FUT", LocalDate.of(2026, 11, 26), 250);
        var far = new FuturesContract("NSE_FO|61500", SPOT, "RELIANCE", "RELIANCE FUT 2", LocalDate.of(2026, 12, 29), 250);
        when(registry.nearFar(eq(SPOT), any())).thenReturn(Optional.of(new NearFar(near, far)));
        var service = mock(UpstoxFnoV3LiveFeedService.class);
        when(service.start(anyList(), anyList())).thenReturn(
                new UpstoxFnoV3LiveFeedService.Status("CONNECTING", 2, 0, 0, 0,
                        null, null, 0, false, false, false, false, 0, null));
        var controller = new UpstoxFnoV3LiveOperatorController(
                mock(UpstoxAnalyticsSource.class), service, CLOCK, registry);
        var response = controller.start(TODAY, false, SPOT);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(service).start(List.of(FUT), List.of(SPOT));
    }

    @Test
    void controllerSpotModeFailsClosedWithoutVerifiedPairAndNeverSubscribes() {
        var registry = mock(FnoFuturesContractRegistry.class);
        when(registry.nearFar(eq(SPOT), any())).thenReturn(Optional.empty());
        var service = mock(UpstoxFnoV3LiveFeedService.class);
        var controller = new UpstoxFnoV3LiveOperatorController(
                mock(UpstoxAnalyticsSource.class), service, CLOCK, registry);
        assertThatThrownBy(() -> controller.start(TODAY, false, SPOT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FNO_SPOT_PAIR_NOT_VERIFIED");
        verify(service, never()).start(anyList(), anyList());
    }

    @Test
    void controllerRejectsMalformedSpotKeyBeforeAnyRegistryOrSocketCall() {
        var registry = mock(FnoFuturesContractRegistry.class);
        var service = mock(UpstoxFnoV3LiveFeedService.class);
        var controller = new UpstoxFnoV3LiveOperatorController(
                mock(UpstoxAnalyticsSource.class), service, CLOCK, registry);
        assertThatThrownBy(() -> controller.start(TODAY, false, "NSE_EQ|INFY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("UPSTOX_FNO_SPOT_KEY_INVALID");
        verifyNoInteractions(registry, service);
    }

    private static <T> List<T> anyList() {
        return org.mockito.ArgumentMatchers.anyList();
    }
}
