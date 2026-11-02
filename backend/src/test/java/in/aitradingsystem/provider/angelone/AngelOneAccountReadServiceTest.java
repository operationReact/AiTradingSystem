package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AngelOneAccountReadServiceTest {

    private static final String JWT = "jwt-token";
    private static final String FRESH_JWT = "fresh-jwt-token";

    private AngelOneSessionManager session;
    private AngelOneApiClient api;
    private AngelOneRateLimiter rateLimiter;
    private AngelOneHealthService health;
    private AngelOneRetryPolicy retryPolicy;
    private AngelOneAccountReadService service;
    private ObjectMapper mapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        session = mock(AngelOneSessionManager.class);
        api = mock(AngelOneApiClient.class);
        rateLimiter = mock(AngelOneRateLimiter.class);
        health = mock(AngelOneHealthService.class);
        retryPolicy = mock(AngelOneRetryPolicy.class);
        mapper = new ObjectMapper();
        service = new AngelOneAccountReadService(session, api, rateLimiter, health, retryPolicy);

        when(retryPolicy.executeSafeRead(anyString(),
                org.mockito.ArgumentMatchers.<Supplier<JsonNode>>any(),
                org.mockito.ArgumentMatchers.<Supplier<JsonNode>>any()))
                .thenAnswer(invocation -> ((Supplier<JsonNode>) invocation.getArgument(1)).get());
    }

    @Test
    void fundsUseDedicatedRmsRateCategory() throws Exception {
        JsonNode data = mapper.readTree("{\"availablecash\":\"1000\"}");
        when(session.accessToken()).thenReturn(JWT);
        when(api.rms(JWT)).thenReturn(data);

        assertThat(service.funds()).isEqualTo(data);

        verify(rateLimiter).acquire(AngelOneRateLimiter.Category.ACCOUNT_FUNDS);
        verify(api).rms(JWT);
        verify(health).recordSuccess();
    }

    @Test
    void holdingsUseConservativePortfolioRateCategory() throws Exception {
        JsonNode data = mapper.readTree("{\"holdings\":[]}");
        when(session.accessToken()).thenReturn(JWT);
        when(api.allHoldings(JWT)).thenReturn(data);

        assertThat(service.allHoldings()).isEqualTo(data);

        verify(rateLimiter).acquire(AngelOneRateLimiter.Category.ACCOUNT_PORTFOLIO);
        verify(api).allHoldings(JWT);
        verify(health).recordSuccess();
    }

    @Test
    void expiredSessionIsRecoveredExactlyOnceForReadOnlyPositionCall() throws Exception {
        JsonNode data = mapper.readTree("[]");
        when(session.accessToken()).thenReturn(JWT);
        when(api.positions(JWT)).thenThrow(new AngelOneException(
                AngelOneException.Kind.SESSION_EXPIRED, "AB8050", "Provider session is no longer valid"));
        when(session.recoverAfterSessionExpiry(JWT)).thenReturn(FRESH_JWT);
        when(api.positions(FRESH_JWT)).thenReturn(data);

        assertThat(service.positions()).isEqualTo(data);

        verify(rateLimiter).acquire(AngelOneRateLimiter.Category.ACCOUNT_PORTFOLIO);
        verify(rateLimiter).acquireForRecovery(AngelOneRateLimiter.Category.ACCOUNT_PORTFOLIO);
        verify(session).recoverAfterSessionExpiry(JWT);
        verify(api).positions(FRESH_JWT);
        verify(health).recordSuccess();
    }

    @Test
    void publishedAccountRateLimitsRemainBounded() {
        assertThat(AngelOneRateLimiter.verifiedLimit(AngelOneRateLimiter.Category.ACCOUNT_FUNDS)
                .perSecondIntervalMs()).isEqualTo(500);
        assertThat(AngelOneRateLimiter.verifiedLimit(AngelOneRateLimiter.Category.ACCOUNT_PORTFOLIO)
                .perSecondIntervalMs()).isEqualTo(1000);
    }
}
