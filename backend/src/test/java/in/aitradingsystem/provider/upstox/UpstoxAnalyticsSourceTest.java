package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import org.springframework.http.HttpStatus;

class UpstoxAnalyticsSourceTest {
    private final SsmClient ssm = mock(SsmClient.class);
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.upstox.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final UpstoxAnalyticsSource source =
            new UpstoxAnalyticsSource(true, ssm, builder.build());

    private void token() {
        when(ssm.getParameter(any(GetParameterRequest.class))).thenAnswer(inv -> {
            GetParameterRequest request = inv.getArgument(0);
            assertThat(request.name()).isEqualTo("/minttick/upstox/analytics-token");
            assertThat(request.withDecryption()).isTrue();
            return GetParameterResponse.builder()
                    .parameter(Parameter.builder().name(request.name())
                            .type(ParameterType.SECURE_STRING).value("unit-test-only").build())
                    .build();
        });
    }

    @Test
    void readsAndValidatesV3DailyCandlesIncludingSeventhOiColumn() {
        token();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/historical-candle/")))
                .andExpect(header("Authorization", "Bearer unit-test-only"))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"candles":[
                      ["2026-11-09T09:15:00+05:30",100,102,99,101,42,0]
                    ]}}
                    """, MediaType.APPLICATION_JSON));
        var rows = source.dailyEquityCandles("NSE_EQ|INE002A01018",
                java.time.LocalDate.parse("2026-11-09"),
                java.time.LocalDate.parse("2026-11-09"));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().volume()).isEqualTo(42);
        assertThat(rows.getFirst().openInterest()).isZero();
        server.verify();
    }

    @Test
    void acceptsProviderNewestFirstAndNormalizesForGoldenValidation() {
        token();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/historical-candle/")))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"candles":[
                      ["2026-11-09T09:17:00+05:30",100,102,99,101,42,0],
                      ["2026-11-09T09:16:00+05:30",100,102,99,101,42,0],
                      ["2026-11-09T09:15:00+05:30",100,102,99,101,42,0]
                    ]}}
                    """, MediaType.APPLICATION_JSON));
        var day = java.time.LocalDate.parse("2026-11-09");
        var rows = source.historicalEquityCandles("NSE_EQ|INE002A01018",
                "minutes", 1, day, day);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).time()).isBefore(rows.get(1).time());
        assertThat(rows.get(1).time()).isBefore(rows.get(2).time());
        server.verify();
    }

    @Test
    void rejectsMixedProviderTimestampOrderBeforeSorting() {
        token();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/historical-candle/")))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"candles":[
                      ["2026-11-09T09:17:00+05:30",100,102,99,101,42,0],
                      ["2026-11-09T09:15:00+05:30",100,102,99,101,42,0],
                      ["2026-11-09T09:16:00+05:30",100,102,99,101,42,0]
                    ]}}
                    """, MediaType.APPLICATION_JSON));
        var day = java.time.LocalDate.parse("2026-11-09");
        assertThatThrownBy(() -> source.historicalEquityCandles(
                "NSE_EQ|INE002A01018", "minutes", 1, day, day))
                .hasMessage("UPSTOX_INVALID_CANDLES");
        server.verify();
    }

    @Test
    void readsActualLastOptionLtpWithExactInstrumentIdentityAndExchangeTime() {
        token();
        Instant traded = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant observed = Instant.now().minusSeconds(3500);
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                "/v3/market-quote/quotes?instrument_key=")))
            .andExpect(header("Authorization", "Bearer unit-test-only"))
            .andRespond(withSuccess(("""
                {"status":"success","data":{"NSE_FO:NIFTY25400CE":{
                  "instrument_token":"NSE_FO|61001",
                  "last_price":120.50,
                  "prev_close_price":110.25,
                  "oi":5000.0,
                  "last_trade_time":"%d",
                  "timestamp":"%s"
                }}}
                """).formatted(traded.toEpochMilli(), observed),
                MediaType.APPLICATION_JSON));
        var result = source.listedFnoLastTrades(java.util.List.of("NSE_FO|61001"));
        assertThat(result).containsOnlyKeys("NSE_FO|61001");
        assertThat(result.get("NSE_FO|61001").ltp()).isEqualByComparingTo("120.50");
        assertThat(result.get("NSE_FO|61001").openInterest()).isEqualTo(5000L);
        assertThat(result.get("NSE_FO|61001").exchangeAt()).isEqualTo(traded);
        server.verify();
    }

    @Test
    void validatesV3ProviderBatchLimitOfFiveHundredInstruments() {
        var maximum = java.util.stream.IntStream.range(0, 500)
            .mapToObj(n -> "NSE_FO|" + (60000 + n)).toList();
        token();
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                "/v3/market-quote/quotes?instrument_key=")))
            .andRespond(withSuccess(
                "{\"status\":\"success\",\"data\":{}}",
                MediaType.APPLICATION_JSON));
        assertThat(source.listedFnoLastTrades(maximum)).isEmpty();
        assertThatThrownBy(() -> source.listedFnoLastTrades(
            java.util.stream.IntStream.range(0, 501)
                .mapToObj(n -> "NSE_FO|" + (60000 + n)).toList()))
            .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void fractionalOiDoesNotInvalidateAVerifiedV3LastPrice() {
        token();
        Instant traded = Instant.now().minusSeconds(3600)
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant observed = traded.plusSeconds(2);
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                "/v3/market-quote/quotes?instrument_key=")))
            .andRespond(withSuccess(("""
                {"status":"success","data":{"NSE_FO:NIFTY":{
                  "instrument_token":"NSE_FO|61001",
                  "last_price":120.50,
                  "oi":5000.5,
                  "last_trade_time":"%d",
                  "timestamp":"%s"
                }}}
                """).formatted(traded.toEpochMilli(), observed),
                MediaType.APPLICATION_JSON));
        var value = source.listedFnoLastTrades(java.util.List.of("NSE_FO|61001"))
            .get("NSE_FO|61001");
        assertThat(value.ltp()).isEqualByComparingTo("120.50");
        assertThat(value.openInterest()).isNull();
        server.verify();
    }

    @Test
    void rejectedDerivativeKeysNeverReachProviderOrSsm() {
        assertThatThrownBy(() -> source.listedFnoLastTrades(
            java.util.List.of("NSE_EQ|INE002A01018")))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ssm);
    }

    @Test
    void readsCurrentRatiosButNeverGrantsPointInTimeHistory() {
        token();
        server.expect(requestTo("https://api.upstox.com/v2/fundamentals/INE002A01018/key-ratios"))
                .andExpect(header("Authorization", "Bearer unit-test-only"))
                .andRespond(withSuccess("""
                    {"status":"success","data":[{"name":"P/E","company_value":"20.15","sector_value":"12.46"}]}
                    """, MediaType.APPLICATION_JSON));
        assertThat(source.currentRatios("INE002A01018")).hasSize(1);
        server.verify();
    }

    @Test
    void disabledOrMalformedInputsNeverReachSsmOrUpstox() {
        UpstoxAnalyticsSource disabled =
                new UpstoxAnalyticsSource(false, ssm, builder.build());
        assertThatThrownBy(() -> disabled.currentRatios("INE002A01018"))
                .hasMessage("UPSTOX_ANALYTICS_DISABLED");
        assertThatThrownBy(() -> source.currentRatios("../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.dailyEquityCandles("NSE_EQ|INE002A01018",
                java.time.LocalDate.parse("2000-01-01"), java.time.LocalDate.parse("2000-03-01")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ssm);
    }

    @Test
    void invalidProviderPayloadIsRejectedWithoutCredentialsInErrors() {
        token();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("historical-candle")))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"candles":[
                      ["2026-11-09T09:15:00+05:30",100,98,99,101,42,0]
                    ]}}
                    """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> source.dailyEquityCandles("NSE_EQ|INE002A01018",
                java.time.LocalDate.parse("2026-11-09"), java.time.LocalDate.parse("2026-11-09")))
                .hasMessage("UPSTOX_INVALID_CANDLES");
        server.verify();
    }

    @Test
    void providerUnauthorizedDoesNotLeakBearerOrResponse() {
        token();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("key-ratios")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("unit-test-only token must not appear"));
        assertThatThrownBy(() -> source.currentRatios("INE002A01018"))
                .hasMessage("UPSTOX_READ_UNAVAILABLE")
                .hasMessageNotContaining("unit-test-only");
        server.verify();
    }

    @Test
    void minuteAndHourWindowsUseV3IntervalsAndStillReadOnly() {
        token();
        // Spring's MockRestServiceServer requires all expectations before the
        // first actual request; these verify sequential read-only GETs.
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                "/minutes/1/2026-10-20/2026-10-20")))
                .andExpect(header("Authorization", "Bearer unit-test-only"))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"candles":[
                      ["2026-10-20T09:15:00+05:30",100,102,99,101,375,0]
                    ]}}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                "/hours/1/2026-10-20/2026-10-20")))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"candles":[
                      ["2026-10-20T09:15:00+05:30",100,102,99,101,5000,0]
                    ]}}
                    """, MediaType.APPLICATION_JSON));
        var on = java.time.LocalDate.parse("2026-10-20");
        var minutes = source.historicalEquityCandles("NSE_EQ|INE002A01018",
                "minutes", 1, on, on);
        assertThat(minutes).hasSize(1);
        assertThat(minutes.getFirst().volume()).isEqualTo(375);
        var hours = source.historicalEquityCandles("NSE_EQ|INE002A01018",
                "hours", 1, on, on);
        assertThat(hours).hasSize(1);
        server.verify();
    }

    @Test
    void largerDailyAndWeeklyWindowsRemainBoundedWithoutPersistence() {
        token();
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                "/days/1/2026-10-20/2026-01-01")))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"candles":[
                      ["2026-10-20T09:15:00+05:30",100,102,99,101,42,0]
                    ]}}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                "/weeks/1/2026-10-20/2025-01-01")))
                .andRespond(withSuccess("""
                    {"status":"success","data":{"candles":[
                      ["2026-10-19T09:15:00+05:30",100,102,99,101,420,0]
                    ]}}
                    """, MediaType.APPLICATION_JSON));
        assertThat(source.historicalEquityCandles("NSE_EQ|INE002A01018",
                "days", 1, java.time.LocalDate.parse("2026-01-01"),
                java.time.LocalDate.parse("2026-10-20"))).hasSize(1);
        assertThat(source.historicalEquityCandles("NSE_EQ|INE002A01018",
                "weeks", 1, java.time.LocalDate.parse("2025-01-01"),
                java.time.LocalDate.parse("2026-10-20"))).hasSize(1);
        server.verify();
    }

    @Test
    void unsupportedGranularitiesAndUnboundedRequestsFailBeforeSsm() {
        var from = java.time.LocalDate.parse("2026-10-01");
        var through = java.time.LocalDate.parse("2026-10-04");
        assertThatThrownBy(() -> source.historicalEquityCandles(
                "NSE_EQ|INE002A01018", "minutes", 1, from, through))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.historicalEquityCandles(
                "NSE_EQ|INE002A01018", "hours", 6, from, through))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.historicalEquityCandles(
                "NSE_EQ|INE002A01018", "weeks", 2, from, through))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.historicalEquityCandles(
                "NSE_EQ|INE002A01018", "../orders", 1, from, through))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.historicalEquityCandles(
                "NSE_EQ|INE002A01018", "days", 1,
                java.time.LocalDate.parse("2000-01-01"), through))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ssm);
    }

    @Test
    void historicalThrottleIsClassifiedWithoutRetryOrProviderDetails() {
        token();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/historical-candle/")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "60")
                        .body("unit-test-only private upstream response"));
        var day = java.time.LocalDate.parse("2026-11-09");
        assertThatThrownBy(() -> source.dailyEquityCandles("NSE_EQ|INE002A01018", day, day))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("UPSTOX_RATE_LIMITED")
                .hasNoCause();
        server.verify();
    }

    @Test
    void probeCooldownAndNonTradingGuarantee() {
        var clock = Clock.fixed(Instant.parse("2026-11-11T08:00:00Z"), ZoneOffset.UTC);
        var unavailable = new UpstoxAnalyticsSource(false, ssm, builder.build());
        var probe = new UpstoxAnalyticsProbeController(unavailable, clock).probe().getBody();
        assertThat(probe.status()).isEqualTo("DISABLED");
        assertThat(probe.stored()).isFalse();
        assertThat(probe.modelTrainingRightsVerified()).isFalse();
        assertThat(probe.liveTradingEnabled()).isFalse();
        verifyNoInteractions(ssm);
    }
}
