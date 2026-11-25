package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.instrument.Instrument;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AngelOneMarketDepthServiceTest {
    private static final Instant AVAILABLE_AT = Instant.parse("2026-11-02T10:00:00Z");
    private static final UUID CATALOGUE_VERSION = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    private final ObjectMapper mapper = new ObjectMapper();
    private AngelOneSessionManager session;
    private AngelOneApiClient api;
    private AngelOneRateLimiter limiter;
    private AngelOneHealthService health;
    private AngelOneMarketDepthService service;

    @BeforeEach
    void setUp() {
        session = mock(AngelOneSessionManager.class);
        api = mock(AngelOneApiClient.class);
        limiter = mock(AngelOneRateLimiter.class);
        health = mock(AngelOneHealthService.class);
        service = new AngelOneMarketDepthService(
                session,
                api,
                limiter,
                health,
                null,
                Clock.fixed(AVAILABLE_AT, ZoneOffset.UTC));
        when(session.accessToken()).thenReturn("jwt-1");
    }

    @Test
    void convertsFullQuoteTopOfBookIntoCausalSpreadEvidence() throws Exception {
        Instrument infy = instrument(3, "1594", "INFY-EQ");
        when(api.fullMarketQuote("jwt-1", List.of("1594"))).thenReturn(data(
                fetchedQuote("NSE", "INFY-EQ", "1594", "02-Nov-2026 15:29:59",
                        "1500.00", "1499.50", 1200, 7, "1500.50", 900, 5),
                "[]"));

        var result = service.fetch(List.of(infy));

        assertThat(result.contractVersion()).isEqualTo("angel-one-market-depth.v1");
        assertThat(result.generatedAt()).isEqualTo(AVAILABLE_AT);
        assertThat(result.dataCutoff()).isEqualTo(AVAILABLE_AT);
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("RESEARCH_MARKET_DEPTH_ONLY");
        assertThat(result.canAuthorizeCapital()).isFalse();

        var row = result.evidence().getFirst();
        assertThat(row.instrumentId()).isEqualTo(3);
        assertThat(row.catalogueVersion()).isEqualTo(CATALOGUE_VERSION.toString());
        assertThat(row.exchange()).isEqualTo("NSE");
        assertThat(row.tradingSymbol()).isEqualTo("INFY-EQ");
        assertThat(row.symbolToken()).isEqualTo("1594");
        assertThat(row.eventAt()).isEqualTo(Instant.parse("2026-11-02T09:59:59Z"));
        assertThat(row.availableAt()).isEqualTo(AVAILABLE_AT);
        assertThat(row.ltp()).isEqualByComparingTo("1500.00");
        assertThat(row.bestBidPrice()).isEqualByComparingTo("1499.50");
        assertThat(row.bestAskPrice()).isEqualByComparingTo("1500.50");
        assertThat(row.midpoint()).isEqualByComparingTo("1500.00");
        assertThat(row.spreadBps()).isEqualByComparingTo("6.666667");
        assertThat(row.bestBidQuantity()).isEqualTo(1200);
        assertThat(row.bestAskQuantity()).isEqualTo(900);
        assertThat(row.source()).isEqualTo("ANGEL_ONE_SMARTAPI_FULL_QUOTE");
        assertThat(row.sourceId()).matches("[a-f0-9]{64}");
        verify(limiter).acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
        verify(health).recordQuoteSuccess();
    }

    @Test
    void unfetchedProviderRowsFailClosed() throws Exception {
        Instrument infy = instrument(3, "1594", "INFY-EQ");
        when(api.fullMarketQuote("jwt-1", List.of("1594"))).thenReturn(data(
                "[]",
                "[{\"exchange\":\"NSE\",\"symbolToken\":\"1594\",\"message\":\"not fetched\"}]"));

        assertThatThrownBy(() -> service.fetch(List.of(infy)))
                .isInstanceOfSatisfying(AngelOneException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(AngelOneException.Kind.INVALID_RESPONSE);
                    assertThat(ex.code()).isEqualTo("MARKET_QUOTE_UNFETCHED");
                });
        verify(health).recordFailure(org.mockito.ArgumentMatchers.any(AngelOneException.class));
    }

    @Test
    void providerIdentityMismatchCannotCrossCatalogueBoundary() throws Exception {
        Instrument infy = instrument(3, "1594", "INFY-EQ");
        when(api.fullMarketQuote("jwt-1", List.of("1594"))).thenReturn(data(
                fetchedQuote("NSE", "WRONG-EQ", "1594", "02-Nov-2026 15:29:59",
                        "1500", "1499.5", 1, 1, "1500.5", 1, 1),
                "[]"));

        assertThatThrownBy(() -> service.fetch(List.of(infy)))
                .isInstanceOfSatisfying(AngelOneException.class,
                        ex -> assertThat(ex.code()).isEqualTo("MARKET_QUOTE_IDENTITY_MISMATCH"));
    }

    @Test
    void crossedTopOfBookFailsClosedInsteadOfFabricatingNegativeSpread() throws Exception {
        Instrument infy = instrument(3, "1594", "INFY-EQ");
        when(api.fullMarketQuote("jwt-1", List.of("1594"))).thenReturn(data(
                fetchedQuote("NSE", "INFY-EQ", "1594", "02-Nov-2026 15:29:59",
                        "1500", "1501", 1, 1, "1500", 1, 1),
                "[]"));

        assertThatThrownBy(() -> service.fetch(List.of(infy)))
                .isInstanceOfSatisfying(AngelOneException.class,
                        ex -> assertThat(ex.code()).isEqualTo("MARKET_QUOTE_CROSSED_BOOK"));
    }

    @Test
    void batchMustUseUniqueNseCashEquityCatalogueIdentities() {
        Instrument infy = instrument(3, "1594", "INFY-EQ");
        Instrument duplicateToken = instrument(4, "1594", "OTHER-EQ");
        Instrument bse = new Instrument(5, "BSE", "BSE", "500112", "SBIN-A", "SBI", "EQ",
                null, null, 1, new BigDecimal("0.05"), CATALOGUE_VERSION);

        assertThatThrownBy(() -> service.fetch(List.of(infy, duplicateToken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique identities");
        assertThatThrownBy(() -> service.fetch(List.of(bse)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NSE cash-equity");
    }

    @Test
    void sessionExpiryRecoversOnceAndReusesTheReservedQuoteSlot() throws Exception {
        Instrument infy = instrument(3, "1594", "INFY-EQ");
        when(api.fullMarketQuote("jwt-1", List.of("1594")))
                .thenThrow(new AngelOneException(
                        AngelOneException.Kind.SESSION_EXPIRED,
                        "AG8001",
                        "Provider session is no longer valid"));
        when(session.recoverAfterSessionExpiry("jwt-1")).thenReturn("jwt-2");
        when(api.fullMarketQuote("jwt-2", List.of("1594"))).thenReturn(data(
                fetchedQuote("NSE", "INFY-EQ", "1594", "02-Nov-2026 15:29:59",
                        "1500", "1499.5", 1, 1, "1500.5", 1, 1),
                "[]"));

        var result = service.fetch(List.of(infy));

        assertThat(result.rowCount()).isEqualTo(1);
        verify(limiter).acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
        verify(limiter).acquireForRecovery(AngelOneRateLimiter.Category.MARKET_QUOTE);
        verify(session).recoverAfterSessionExpiry("jwt-1");
        verify(health).recordQuoteSuccess();
    }

    private Instrument instrument(long id, String token, String tradingSymbol) {
        return new Instrument(
                id,
                "NSE",
                "NSE",
                token,
                tradingSymbol,
                tradingSymbol.replace("-EQ", ""),
                "EQ",
                null,
                null,
                1,
                new BigDecimal("0.05"),
                CATALOGUE_VERSION);
    }

    private JsonNode data(String fetched, String unfetched) throws Exception {
        return mapper.readTree("{\"fetched\":" + fetched + ",\"unfetched\":" + unfetched + "}");
    }

    private String fetchedQuote(String exchange, String tradingSymbol, String token, String feedTime,
                                String ltp, String bid, long bidQuantity, long bidOrders,
                                String ask, long askQuantity, long askOrders) {
        return "[{"
                + "\"exchange\":\"" + exchange + "\","
                + "\"tradingSymbol\":\"" + tradingSymbol + "\","
                + "\"symbolToken\":\"" + token + "\","
                + "\"ltp\":" + ltp + ","
                + "\"exchFeedTime\":\"" + feedTime + "\","
                + "\"depth\":{"
                + "\"buy\":[{\"price\":" + bid + ",\"quantity\":" + bidQuantity
                + ",\"orders\":" + bidOrders + "}],"
                + "\"sell\":[{\"price\":" + ask + ",\"quantity\":" + askQuantity
                + ",\"orders\":" + askOrders + "}]}}]";
    }
}
