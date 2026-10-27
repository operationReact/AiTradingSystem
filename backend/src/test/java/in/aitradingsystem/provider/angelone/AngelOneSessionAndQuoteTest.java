package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.AngelOneProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AngelOneSessionAndQuoteTest {

    private static final String ROOT = AngelOneApiClientTest.ROOT;
    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    private MutableClock clock;
    private MockRestServiceServer server;
    private AngelOneProperties props;
    private AngelOneSessionManager session;
    private AngelOneHealthService health;
    private AngelOneQuoteService quotes;
    private AngelOneRateLimiter limiter;

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Kolkata");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2025-01-06T04:00:00Z"));
        build(credentials(), 3600, 0, 0);
    }

    private static String[] credentials() {
        return new String[] {AngelOneApiClientTest.API_KEY, "CLIENT1", "pin-secret", SECRET};
    }

    private void build(String[] creds, long maxAgeSeconds, int loginInterval, int quoteInterval) {
        props = new AngelOneProperties(creds[0], creds[1], creds[2], creds[3], ROOT, 5000, 10000,
                maxAgeSeconds, loginInterval, quoteInterval, 150);
        RestClient.Builder builder = RestClient.builder().baseUrl(ROOT);
        server = MockRestServiceServer.bindTo(builder).build();
        AngelOneApiClient api = new AngelOneApiClient(builder.build(), props, new ObjectMapper());
        limiter = new AngelOneRateLimiter(props);
        session = new AngelOneSessionManager(props, api, limiter, clock);
        health = new AngelOneHealthService(session, clock);
        quotes = new AngelOneQuoteService(session, api, limiter, health);
    }

    private void expectLogin(String jwt) {
        expectLogin(jwt, clock.instant());
    }

    private void expectLogin(String jwt, Instant at) {
        String totp = AngelOneTotp.generate(SECRET, at);
        server.expect(requestTo(ROOT + AngelOneApiClient.LOGIN_PATH))
                .andExpect(content().string(containsString("\"totp\":\"" + totp + "\"")))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"jwtToken\":\"" + jwt
                        + "\",\"refreshToken\":\"r\"}}", MediaType.APPLICATION_JSON));
    }

    private void expectLtp(String bearer, String ltp) {
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andExpect(header("Authorization", "Bearer " + bearer))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"ltp\":" + ltp + "}}", MediaType.APPLICATION_JSON));
    }

    @Test
    void unconfiguredCredentialsNeverCallProvider() {
        build(new String[] {"", "CLIENT1", "pin-secret", SECRET}, 3600, 0, 0);

        assertThat(health.status().state()).isEqualTo(AngelOneHealthService.State.NOT_CONFIGURED);
        assertThatThrownBy(() -> session.accessToken())
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.NOT_CONFIGURED));
        server.verify();
    }

    @Test
    void validSessionIsReusedWithoutRelogin() {
        expectLogin("jwt-1");

        assertThat(session.accessToken()).isEqualTo("jwt-1");
        assertThat(session.accessToken()).isEqualTo("jwt-1");
        server.verify();
    }

    @Test
    void sessionReloginsAfterMaxAge() {
        Instant t0 = clock.instant();
        expectLogin("jwt-1", t0);
        expectLogin("jwt-2", t0.plusSeconds(3601));
        session.accessToken();
        clock.advance(Duration.ofSeconds(3601));
        assertThat(session.accessToken()).isEqualTo("jwt-2");
        server.verify();
    }

    @Test
    void quoteRetriesOnceAfterSessionExpiryAndRecordsHealth() {
        expectLogin("jwt-1");
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(withSuccess("{\"status\":false,\"errorcode\":\"AG8003\",\"message\":\"x\"}",
                        MediaType.APPLICATION_JSON));
        expectLogin("jwt-2");
        expectLtp("jwt-2", "250.5");

        BigDecimal ltp = quotes.ltp("NSE", "RELIANCE-EQ", "2885");

        assertThat(ltp).isEqualByComparingTo("250.5");
        assertThat(health.status().state()).isEqualTo(AngelOneHealthService.State.AUTHENTICATED);
        server.verify();
    }

    @Test
    void recoveryWaitsForAuthenticationAndQuoteThrottleWithinBoundedWindow() {
        build(credentials(), 3600, 30, 100);
        expectLogin("jwt-1");
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(withSuccess("{\"status\":false,\"errorcode\":\"AG8001\",\"message\":\"expired\"}",
                        MediaType.APPLICATION_JSON));
        expectLogin("jwt-2");
        expectLtp("jwt-2", "105.25");

        assertThat(quotes.ltp("NSE", "RELIANCE-EQ", "2885")).isEqualByComparingTo("105.25");
        assertThat(health.status().lastQuoteAt()).isNotNull();
        assertThat(health.status().sessionActive()).isTrue();
        assertThat(health.status().sessionAgeSeconds()).isNotNull();
        server.verify();
    }

    @Test
    void staleExpiredRequestCannotInvalidateNewerSession() {
        build(credentials(), 3600, 0, 0);
        expectLogin("jwt-1");
        expectLogin("jwt-2");

        String first = session.accessToken();
        String refreshed = session.recoverAfterSessionExpiry(first);

        assertThat(refreshed).isEqualTo("jwt-2");
        assertThat(session.recoverAfterSessionExpiry(first)).isEqualTo("jwt-2");
        assertThat(session.hasActiveSession()).isTrue();
        server.verify();
    }

    @Test
    void invalidTotpSecretIsCategorizedWithoutCallingProvider() {
        build(new String[] {AngelOneApiClientTest.API_KEY, "CLIENT1", "pin-secret", "not*base32!"},
                3600, 0, 0);

        assertThatThrownBy(() -> session.accessToken())
                .isInstanceOfSatisfying(AngelOneException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AngelOneException.Kind.NOT_CONFIGURED);
                    assertThat(e.code()).isEqualTo("INVALID_TOTP_SECRET");
                });
        server.verify();
    }

    @Test
    void persistentSessionExpiryIsReportedAsSessionExpired() {
        expectLogin("jwt-1");
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectLogin("jwt-2");
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> quotes.ltp("NSE", "RELIANCE-EQ", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.SESSION_EXPIRED));
        assertThat(health.status().state()).isEqualTo(AngelOneHealthService.State.SESSION_EXPIRED);
    }

    @Test
    void quoteRequestsAreThrottledLocally() {
        build(credentials(), 3600, 0, 60_000);
        expectLogin("jwt-1");
        expectLtp("jwt-1", "100");

        quotes.ltp("NSE", "RELIANCE-EQ", "2885");

        assertThatThrownBy(() -> quotes.ltp("NSE", "RELIANCE-EQ", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.RATE_LIMITED));
        server.verify();
    }

    @Test
    void sessionExpiryRetryIsThrottledAsItsOwnRequest() {
        build(credentials(), 3600, 0, 60_000);
        expectLogin("jwt-1");
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(withSuccess("{\"status\":false,\"errorcode\":\"AG8001\",\"message\":\"x\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> quotes.ltp("NSE", "RELIANCE-EQ", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.RATE_LIMITED));
        server.verify();
    }

    @Test
    void sessionExpiryRetryAcquiresLimiterOnceAndSendsExactlyTwoLtpRequests() {
        build(credentials(), 3600, 0, 0);
        expectLogin("jwt-1");
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(withSuccess("{\"status\":false,\"errorcode\":\"AG8001\",\"message\":\"x\"}",
                        MediaType.APPLICATION_JSON));
        expectLogin("jwt-2");
        expectLtp("jwt-2", "99.5");

        assertThat(quotes.ltp("NSE", "RELIANCE-EQ", "2885")).isEqualByComparingTo("99.5");
        server.verify();
    }

    @Test
    void zeroLtpIsRejectedAsInvalid() {
        expectLogin("jwt-1");
        expectLtp("jwt-1", "0");

        assertThatThrownBy(() -> quotes.ltp("NSE", "RELIANCE-EQ", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.INVALID_RESPONSE));
    }

    @Test
    void loginIsThrottledLocally() {
        build(credentials(), 3600, 60_000, 0);
        expectLogin("jwt-1");

        session.login();

        assertThatThrownBy(() -> session.login())
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.RATE_LIMITED));
    }

    @Test
    void failedLoginLeavesNoSessionAndTestConnectionRecordsFailure() {
        server.expect(requestTo(ROOT + AngelOneApiClient.LOGIN_PATH))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        AngelOneHealthService.ProviderHealth result = health.testConnection();

        assertThat(session.hasActiveSession()).isFalse();
        assertThat(result.state()).isEqualTo(AngelOneHealthService.State.UNAVAILABLE);
        assertThat(result.lastErrorCode()).isEqualTo("HTTP_500");
    }

    @Test
    void recoveryFailsWithinConfiguredWaitRatherThanSleepingForProviderInterval() {
        build(credentials(), 3600, 0, 60_000);
        expectLogin("jwt-1");
        server.expect(requestTo(ROOT + AngelOneApiClient.LTP_PATH))
                .andRespond(withSuccess("{\"status\":false,\"errorcode\":\"AG8001\",\"message\":\"expired\"}",
                        MediaType.APPLICATION_JSON));

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> quotes.ltp("NSE", "RELIANCE-EQ", "2885"))
                .isInstanceOfSatisfying(AngelOneException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AngelOneException.Kind.RATE_LIMITED);
                    assertThat(e.code()).isEqualTo("LOCAL_RATE_LIMIT");
                });
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertThat(elapsedMs).isLessThan(1_500);
        server.verify();
    }
}
