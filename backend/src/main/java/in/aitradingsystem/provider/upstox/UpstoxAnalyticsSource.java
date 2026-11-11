package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterType;

/**
 * Opt-in read-only Upstox Analytics Token source, completely independent from
 * customer OAuth and the existing Angel One history collector.
 *
 * No order endpoints, public controllers, writes, scheduler, storage or model
 * eligibility are granted by this client. Source rights must be reviewed
 * before persisted ingestion, training, inference or distribution.
 */
@Component
public final class UpstoxAnalyticsSource {
    static final String PARAMETER_NAME = "/minttick/upstox/analytics-token";
    private static final String ORIGIN = "https://api.upstox.com";
    private static final String EQUITY_KEY = "(NSE_EQ|BSE_EQ)\\|[A-Z0-9]{12}";
    private static final String ISIN = "[A-Z]{2}[A-Z0-9]{10}";
    private static final int MAX_CANDLES = 40;
    private final boolean enabled;
    private final SsmClient suppliedSsm;
    private final RestClient client;

    @Autowired
    public UpstoxAnalyticsSource(
            @Value("${MINTTICK_UPSTOX_ANALYTICS_ENABLED:false}") boolean enabled,
            RestClient.Builder builder) {
        this(enabled, null, builder);
    }

    UpstoxAnalyticsSource(boolean enabled, SsmClient suppliedSsm, RestClient.Builder builder) {
        this.enabled = enabled;
        this.suppliedSsm = suppliedSsm;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.client = builder.baseUrl(ORIGIN).requestFactory(factory).build();
    }

    public boolean enabled() {
        return enabled;
    }

    public record Candle(Instant time, BigDecimal open, BigDecimal high,
                         BigDecimal low, BigDecimal close, long volume, long openInterest) {}
    public record Ratio(String name, String companyValue, String sectorValue) {}

    /** At most one 31-day cash-equity daily window, with full source identity. */
    public List<Candle> dailyEquityCandles(String instrumentKey, LocalDate from, LocalDate through) {
        if (instrumentKey == null || !instrumentKey.matches(EQUITY_KEY)
                || from == null || through == null || from.isAfter(through)
                || ChronoUnit.DAYS.between(from, through) > 30
                || through.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")))) {
            throw new IllegalArgumentException("Invalid bounded Upstox daily-candle request");
        }
        JsonNode root = get("/v3/historical-candle/{key}/days/1/{to}/{from}",
                instrumentKey, through.toString(), from.toString());
        JsonNode data = root.path("data").path("candles");
        if (!data.isArray() || data.size() > MAX_CANDLES) {
            throw unavailable("UPSTOX_INVALID_CANDLES");
        }
        List<Candle> candles = new ArrayList<>();
        Set<Instant> times = new HashSet<>();
        for (JsonNode row : data) {
            if (!row.isArray() || row.size() != 7) throw unavailable("UPSTOX_INVALID_CANDLES");
            try {
                Instant at = OffsetDateTime.parse(row.get(0).asText()).toInstant();
                BigDecimal open = row.get(1).decimalValue();
                BigDecimal high = row.get(2).decimalValue();
                BigDecimal low = row.get(3).decimalValue();
                BigDecimal close = row.get(4).decimalValue();
                long volume = row.get(5).bigIntegerValue().longValueExact();
                long oi = row.get(6).bigIntegerValue().longValueExact();
                LocalDate session = at.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
                if (session.isBefore(from) || session.isAfter(through)
                        || !times.add(at) || volume < 0 || oi < 0
                        || low.signum() <= 0 || open.signum() <= 0 || close.signum() <= 0
                        || high.compareTo(low) < 0 || high.compareTo(open) < 0
                        || high.compareTo(close) < 0 || low.compareTo(open) > 0
                        || low.compareTo(close) > 0) throw unavailable("UPSTOX_INVALID_CANDLES");
                candles.add(new Candle(at, open, high, low, close, volume, oi));
            } catch (RuntimeException ex) {
                throw unavailable("UPSTOX_INVALID_CANDLES");
            }
        }
        candles.sort(Comparator.comparing(Candle::time));
        return List.copyOf(candles);
    }

    /** Current ratios only. Never use as historical point-in-time features. */
    public List<Ratio> currentRatios(String isin) {
        if (isin == null || !isin.matches(ISIN)) {
            throw new IllegalArgumentException("Invalid ISIN");
        }
        JsonNode data = get("/v2/fundamentals/{isin}/key-ratios", isin).path("data");
        if (!data.isArray() || data.size() > 32) throw unavailable("UPSTOX_INVALID_RATIOS");
        List<Ratio> values = new ArrayList<>();
        for (JsonNode row : data) {
            String name = row.path("name").asText("");
            String companyValue = row.path("company_value").asText("");
            String sectorValue = row.path("sector_value").asText("");
            if (name.isBlank() || name.length() > 80
                    || companyValue.length() > 80 || sectorValue.length() > 80) {
                throw unavailable("UPSTOX_INVALID_RATIOS");
            }
            values.add(new Ratio(name, companyValue, sectorValue));
        }
        return List.copyOf(values);
    }

    private JsonNode get(String path, Object... pathVariables) {
        String token = token();
        try {
            JsonNode body = client.get().uri(path, pathVariables)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(JsonNode.class);
            if (body == null || !"success".equals(body.path("status").asText())
                    || body.path("data").isMissingNode()) {
                throw unavailable("UPSTOX_INVALID_RESPONSE");
            }
            return body;
        } catch (RuntimeException ex) {
            // Never expose upstream body, request headers, token, AWS errors or URL.
            throw unavailable("UPSTOX_READ_UNAVAILABLE");
        }
    }

    private String token() {
        if (!enabled) throw unavailable("UPSTOX_ANALYTICS_DISABLED");
        try {
            software.amazon.awssdk.services.ssm.model.Parameter parameter;
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(PARAMETER_NAME).withDecryption(true).build();
            if (suppliedSsm != null) {
                parameter = suppliedSsm.getParameter(request).parameter();
            } else {
                try (SsmClient ssm = SsmClient.builder().region(Region.AP_SOUTH_1).build()) {
                    parameter = ssm.getParameter(request).parameter();
                }
            }
            if (parameter == null || parameter.type() != ParameterType.SECURE_STRING
                    || parameter.value() == null || parameter.value().isBlank()) {
                throw unavailable("UPSTOX_SSM_UNAVAILABLE");
            }
            return parameter.value();
        } catch (RuntimeException ex) {
            throw unavailable("UPSTOX_SSM_UNAVAILABLE");
        }
    }

    private static IllegalStateException unavailable(String code) {
        return new IllegalStateException(code);
    }
}
