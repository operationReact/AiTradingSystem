package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class CustomerMindFnoResearchControllerTest {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 26);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final CustomerFnoDiscoveryController discovery = mock(CustomerFnoDiscoveryController.class);
    private final InstrumentMasterService instruments = mock(InstrumentMasterService.class);
    private final MarketDataRepository candles = mock(MarketDataRepository.class);
    private final UpstoxAnalyticsSource analytics = mock(UpstoxAnalyticsSource.class);
    private final Authentication auth = mock(Authentication.class);
    private final CustomerMindFnoResearchController controller =
        new CustomerMindFnoResearchController(discovery, instruments, candles, analytics,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setup() {
        when(discovery.capability(auth)).thenReturn(ResponseEntity.ok(
            new CustomerFnoDiscoveryController.Capability(
                true, true, true, true, "LIVE_QUOTES", "CONNECTED", 2, NOW, true)));
        when(discovery.expiries(auth)).thenReturn(ResponseEntity.ok(
            new CustomerPaperFnoMarketController.Expiries(
                FnoLongOptionCrossoverV1.UNDERLYING, "VERIFIED", NOW, List.of(EXPIRY))));
        when(instruments.activeInstrumentBySymbol("NSE", "Nifty 50")).thenReturn(
            java.util.Optional.of(new Instrument(42L, "NSE_CM", "NSE",
                "99926000", "Nifty 50", "Nifty 50", "AMXIDX",
                null, null, 1, new BigDecimal("0.05"), null)));
    }

    private static List<MarketCandle> bars(String lastPrice) {
        List<MarketCandle> result = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            LocalDate session = LocalDate.of(2026, 11, 19).minusDays(34 - i);
            Instant closeAt = session.atTime(LocalTime.of(15, 30)).atZone(IST).toInstant();
            BigDecimal close = new BigDecimal(i == 34 ? lastPrice : "100");
            result.add(new MarketCandle(i + 1L, 42L, "NSE", "Nifty 50",
                "99926000", null, "ONE_DAY", closeAt.minusSeconds(6 * 3600),
                close, close, close, close, 100L,
                "ANGEL_ONE_SMARTAPI", closeAt.plusSeconds(60)));
        }
        return result;
    }

    private static CustomerPaperFnoMarketController.Leg leg(String side, boolean fresh) {
        return new CustomerPaperFnoMarketController.Leg(
            "NSE_FO|61001" + side, "NIFTY 25300 " + side, side,
            65, new BigDecimal("0.05"), new BigDecimal("125.00"),
            new BigDecimal("126.00"), NOW.minusSeconds(fresh ? 2 : 60),
            NOW.minusSeconds(fresh ? 1 : 59), fresh, false,
            new BigDecimal("125.50"), new BigDecimal("120"), 1200L,
            new BigDecimal("0.15"), new BigDecimal("0.5"), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private void quotes(boolean callFresh, boolean putFresh) {
        var market = new CustomerPaperFnoMarketController.Market(
            FnoLongOptionCrossoverV1.UNDERLYING, EXPIRY,
            "UPSTOX_ANALYTICS", "FRESH_QUOTES", NOW,
            false, true, false, false,
            List.of(new CustomerPaperFnoMarketController.Strike(
                new BigDecimal("25300"), leg("CE", callFresh), leg("PE", putFresh))));
        when(discovery.market(EXPIRY, auth)).thenReturn(ResponseEntity.ok(market));
    }

    @Test
    void actualSourceBackedCrossoverProducesReadOnlyCallResearchWithListedContract() {
        when(candles.recentCandles(42L, "ONE_DAY", 64)).thenReturn(bars("125"));
        quotes(true, true);
        var r = controller.evaluate(auth);
        assertThat(r.getHeaders().getCacheControl()).contains("no-store");
        var result = r.getBody();
        assertThat(result.status()).isEqualTo("RESEARCH_CANDIDATE");
        assertThat(result.signal()).isEqualTo("BUY_CALL_RESEARCH");
        assertThat(result.strategyId()).isEqualTo("strategy.STR-181");
        assertThat(result.expiry()).isEqualTo(EXPIRY);
        assertThat(result.instrumentKey()).isEqualTo("NSE_FO|61001CE");
        assertThat(result.bid()).isEqualByComparingTo("125.00");
        assertThat(result.ltp()).isEqualByComparingTo("125.50");
        assertThat(result.openInterest()).isEqualTo(1200L);
        assertThat(result.iv()).isEqualByComparingTo("0.15");
        assertThat(result.completedCandles()).isEqualTo(35);
        assertThat(result.executable()).isFalse();
        assertThat(result.orderPlaced()).isFalse();
    }

    @Test
    void actualSourceBackedPutCrossoverStaysResearchOnly() {
        when(candles.recentCandles(42L, "ONE_DAY", 64)).thenReturn(bars("75"));
        quotes(true, true);
        var result = controller.inspect(auth);
        assertThat(result.status()).isEqualTo("RESEARCH_CANDIDATE");
        assertThat(result.signal()).isEqualTo("BUY_PUT_RESEARCH");
        assertThat(result.strategyId()).isEqualTo("strategy.STR-182");
        assertThat(result.instrumentKey()).isEqualTo("NSE_FO|61001PE");
        assertThat(result.executable()).isFalse();
    }

    @Test
    void noCrossoverDoesNotFetchQuotesOrSuggestAnOrder() {
        when(candles.recentCandles(42L, "ONE_DAY", 64)).thenReturn(bars("100"));
        var result = controller.inspect(auth);
        assertThat(result.status()).isEqualTo("NO_TRADE");
        assertThat(result.signal()).isEqualTo("NO_TRADE");
        assertThat(result.instrumentKey()).isNull();
        verify(discovery, never()).market(any(), any());
    }

    @Test
    void staleProviderQuotesNeverProduceBuyResearch() {
        when(candles.recentCandles(42L, "ONE_DAY", 64)).thenReturn(bars("125"));
        quotes(true, false);
        var result = controller.inspect(auth);
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.signal()).isEqualTo("NO_TRADE");
        assertThat(result.reasonCode()).isEqualTo("FNO_PAIRED_FRESH_QUOTES_UNAVAILABLE");
    }

    @Test
    void missingDisplayRightsNeverReadsIndexOrSourceContracts() {
        when(discovery.capability(auth)).thenReturn(ResponseEntity.ok(
            new CustomerFnoDiscoveryController.Capability(
                false, true, true, true, "DISPLAY_RIGHTS_NOT_VERIFIED",
                "STOPPED", 0, null, false)));
        var result = controller.inspect(auth);
        assertThat(result.reasonCode()).isEqualTo("FNO_DISPLAY_RIGHTS_NOT_VERIFIED");
        assertThat(result.signal()).isEqualTo("NO_TRADE");
        verifyNoInteractions(instruments, candles);
        verify(discovery, never()).expiries(any());
    }

    @Test
    void closedMarketDoesNotEmitFreshResearchCandidate() {
        when(discovery.capability(auth)).thenReturn(ResponseEntity.ok(
            new CustomerFnoDiscoveryController.Capability(
                true, true, true, false, "MARKET_CLOSED", "STOPPED", 0, null, true)));
        var result = controller.inspect(auth);
        assertThat(result.status()).isEqualTo("NO_TRADE");
        assertThat(result.reasonCode()).isEqualTo("MARKET_CLOSED");
        verifyNoInteractions(instruments, candles);
    }

    @Test
    void noIndexHistoryFallsBackToBoundedRealUpstoxIndexRead() {
        when(instruments.activeInstrumentBySymbol("NSE", "Nifty 50"))
            .thenReturn(java.util.Optional.empty());
        var sourceBars = new ArrayList<UpstoxAnalyticsSource.Candle>();
        for (var bar : bars("125")) {
            sourceBars.add(new UpstoxAnalyticsSource.Candle(
                bar.candleTime(), bar.open(), bar.high(), bar.low(), bar.close(),
                bar.volume(), 0L));
        }
        when(analytics.historicalNiftyIndexDailyCandles(
            LocalDate.of(2026, 11, 20).minusDays(110), LocalDate.of(2026, 11, 20)))
            .thenReturn(sourceBars);
        quotes(true, true);
        var result = controller.inspect(auth);
        assertThat(result.status()).isEqualTo("RESEARCH_CANDIDATE");
        assertThat(result.strategyId()).isEqualTo("strategy.STR-181");
        assertThat(result.candleSource()).isEqualTo("UPSTOX_ANALYTICS");
        assertThat(result.executable()).isFalse();
        verifyNoInteractions(candles);
    }

    @Test
    void forwardKnownOrUnlicensedIndexCandlesCannotEmitSignal() {
        var invalid = bars("125");
        var last = invalid.getLast();
        invalid.set(invalid.size() - 1,
            new MarketCandle(last.id(), last.instrumentId(), last.exchange(),
                last.tradingSymbol(), last.symbolToken(), last.catalogueVersion(),
                last.interval(), last.candleTime(), last.open(), last.high(),
                last.low(), last.close(), last.volume(), "UNKNOWN",
                last.fetchedAt()));
        when(candles.recentCandles(42L, "ONE_DAY", 64)).thenReturn(invalid);
        var result = controller.inspect(auth);
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reasonCode()).isEqualTo("NIFTY_CANDLE_PROVENANCE_INVALID");
        verify(discovery, never()).market(any(), any());
    }

    @Test
    void invalidCustomerAuthenticationPropagatesInsteadOfBeingConvertedToBlocked() {
        when(discovery.capability(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer login required"));
        assertThatThrownBy(() -> controller.inspect(auth))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("401");
        verifyNoInteractions(instruments, candles);
    }
}
