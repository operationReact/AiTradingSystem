package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final int MAX_RESPONSE_CHARS = 256 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EQUITY_KEY = "(NSE_EQ|BSE_EQ)\\|[A-Z0-9]{12}";
    private static final String ISIN = "[A-Z]{2}[A-Z0-9]{10}";
    private static final int MAX_CANDLES = 1024;
    private final boolean enabled;
    private final SsmClient suppliedSsm;
    private final RestClient client;

    @Autowired
    public UpstoxAnalyticsSource(@Value("${MINTTICK_UPSTOX_ANALYTICS_ENABLED:false}") boolean enabled) {
        this(enabled, null, productionHttp(RestClient.builder()));
    }

    private static RestClient productionHttp(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return builder.baseUrl(ORIGIN).requestFactory(factory).build();
    }

    UpstoxAnalyticsSource(boolean enabled, SsmClient suppliedSsm, RestClient http) {
        this.enabled = enabled;
        this.suppliedSsm = suppliedSsm;
        this.client = http;
    }

    public boolean enabled() {
        return enabled;
    }

    public record Candle(Instant time, BigDecimal open, BigDecimal high,
                         BigDecimal low, BigDecimal close, long volume, long openInterest) {}
    public record Ratio(String name, String companyValue, String sectorValue) {}

    /** Preserve the conservative existing operator probe's 31-day daily window. */
    public List<Candle> dailyEquityCandles(String instrumentKey, LocalDate from, LocalDate through) {
        if (from == null || through == null || from.isAfter(through)
                || ChronoUnit.DAYS.between(from, through) > 30) {
            throw new IllegalArgumentException("Invalid bounded Upstox daily-candle request");
        }
        return historicalEquityCandles(instrumentKey, "days", 1, from, through);
    }

    /**
     * Read one bounded V3 equity history window. No persistence, scheduling, order
     * actions or broker OAuth session access. Callers must checkpoint separately,
     * honor the published API quotas, and verify dataset usage rights.
     *
     * Deliberately tighter windows than Upstox's documented maxima keep a
     * single response inside our size and candle-count bounds.
     */
    public List<Candle> historicalEquityCandles(String instrumentKey, String unit,
                                                int interval, LocalDate from, LocalDate through) {
        long maxDays = allowedWindowDays(unit, interval);
        if (instrumentKey == null || !instrumentKey.matches(EQUITY_KEY)
                || maxDays < 0 || from == null || through == null
                || from.isAfter(through)
                || ChronoUnit.DAYS.between(from, through) > maxDays
                || through.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")))) {
            throw new IllegalArgumentException("Invalid bounded Upstox historical-candle request");
        }
        return readValidatedHistoricalCandles(instrumentKey, unit, interval, from, through);
    }

    /**
     * Ephemeral provider-listed NIFTY 50 index bars for an authenticated
     * research request. This method is bounded and index-specific: never
     * accepts arbitrary symbols, stores bars, trains models or places orders.
     * The caller must separately enforce research/source permissions.
     */
    public List<Candle> historicalNiftyIndexDailyCandles(LocalDate from, LocalDate through) {
        if (!enabled || from == null || through == null || from.isAfter(through)
                || ChronoUnit.DAYS.between(from, through) > 120
                || through.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")))) {
            throw new IllegalArgumentException("Invalid bounded NIFTY index history request");
        }
        return readValidatedHistoricalCandles("NSE_INDEX|Nifty 50", "days", 1, from, through);
    }

    private List<Candle> readValidatedHistoricalCandles(String instrumentKey, String unit,
            int interval, LocalDate from, LocalDate through) {
        JsonNode root = get("/v3/historical-candle/{key}/{unit}/{interval}/{to}/{from}",
                instrumentKey, unit, interval, through.toString(), from.toString());
        JsonNode data = root.path("data").path("candles");
        if (!data.isArray() || data.size() > MAX_CANDLES) {
            throw unavailable("UPSTOX_INVALID_CANDLES");
        }
        List<Candle> candles = new ArrayList<>();
        Set<Instant> times = new HashSet<>();
        Instant previousProviderTime = null;
        int providerDirection = 0;
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
                // Upstox may return newest-first. Preserve the original order long
                // enough to reject a mixed/oscillating response before normalizing.
                if (previousProviderTime != null) {
                    int direction = Integer.signum(at.compareTo(previousProviderTime));
                    if (direction == 0 || (providerDirection != 0 && direction != providerDirection))
                        throw unavailable("UPSTOX_INVALID_CANDLES");
                    providerDirection = direction;
                }
                previousProviderTime = at;
                candles.add(new Candle(at, open, high, low, close, volume, oi));
            } catch (RuntimeException ex) {
                throw unavailable("UPSTOX_INVALID_CANDLES");
            }
        }
        candles.sort(Comparator.comparing(Candle::time));
        return List.copyOf(candles);
    }


    /**
     * Inclusive windows: one or two calendar days for fast minute bars, a
     * month for slower intraday bars, one year for daily and ten years for
     * weekly/monthly. These are application ceilings, not provider guarantees.
     */
    static long allowedWindowDays(String unit, int interval) {
        if (unit == null) return -1;
        return switch (unit) {
            case "minutes" -> interval >= 1 && interval <= 300
                    ? (interval <= 15 ? 1L : 30L) : -1L;
            case "hours" -> interval >= 1 && interval <= 5 ? 30L : -1L;
            case "days" -> interval == 1 ? 365L : -1L;
            case "weeks", "months" -> interval == 1 ? 3650L : -1L;
            default -> -1L;
        };
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


    // ---- Read-only F&O source, issue #1063. No persistence, training or orders. ----

    public record FnoOptionContract(String underlyingKey, String instrumentKey,
                                    LocalDate expiry, BigDecimal strikePrice, String side,
                                    int lotSize, BigDecimal tickSize, String tradingSymbol) {}

    public record FnoOptionQuote(String instrumentKey, BigDecimal ltp,
                                 BigDecimal bidPrice, BigDecimal askPrice,
                                 long volume, long openInterest, long previousOpenInterest,
                                 BigDecimal iv, BigDecimal delta, BigDecimal gamma,
                                 BigDecimal theta, BigDecimal vega) {}

    public record FnoOptionStrike(LocalDate expiry, String underlyingKey,
                                  BigDecimal strikePrice, BigDecimal underlyingSpotPrice,
                                  FnoOptionQuote call, FnoOptionQuote put) {}

    /**
     * retrievedAt is our HTTP observation time, NOT the exchange tick timestamp.
     * The option-chain response has no documented per-leg trade timestamps, so
     * downstream execution must NEVER treat this as a fresh executable quote.
     */
    public record OptionChainSnapshot(String source, Instant retrievedAt,
                                      boolean exchangeQuoteTimeVerified,
                                      List<FnoOptionStrike> strikes) {}

    /**
     * Discover exchange-listed NIFTY expiries from bounded provider-defined
     * relative buckets, never from a guessed calendar or a static fixture.
     * All values are ephemeral; no prices, contract IDs or raw provider
     * responses are returned to a customer. Call only after a separate
     * customer-display-permission gate has passed.
     *
     * Upstox documents current/next/far week and month for the Option
     * Contracts endpoint. Each request returns one bounded expiry bucket,
     * avoiding a potentially oversized all-expiries response.
     */
    public List<LocalDate> currentNiftyOptionExpiries(Instant now) {
        if (!enabled) throw unavailable("UPSTOX_ANALYTICS_DISABLED");
        if (now == null) throw new IllegalArgumentException("UPSTOX_FNO_INVALID_REQUEST");
        LocalDate today = now.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
        LocalDate last = today.plusDays(90);
        var pairs = new java.util.TreeMap<LocalDate, java.util.Set<String>>();
        for (String period : List.of("current_week", "next_week", "far_week",
                                     "current_month", "next_month", "far_month")) {
            JsonNode rows = get(
                "/v2/option/contract?instrument_key={underlying}&expiry_date={period}",
                "NSE_INDEX|Nifty 50", period).path("data");
            // An empty relative bucket is valid: other listed expiries may exist.
            // Malformed and oversized provider responses still fail closed.
            if (!rows.isArray() || rows.size() > 1024)
                throw unavailable("UPSTOX_FNO_EXPIRY_SOURCE_UNAVAILABLE");
            Set<String> seen = new HashSet<>();
            for (JsonNode row : rows) {
                try {
                    if (!"NSE_INDEX|Nifty 50".equals(fnoText(row, "underlying_key"))
                            || !"NSE_FO".equals(fnoText(row, "segment"))
                            || !"NSE".equals(fnoText(row, "exchange"))) {
                        throw new IllegalArgumentException();
                    }
                    String key = fnoContractKey(row, "instrument_key");
                    String side = fnoText(row, "instrument_type");
                    if (!("CE".equals(side) || "PE".equals(side)) || !seen.add(key))
                        throw new IllegalArgumentException();
                    LocalDate expiry = LocalDate.parse(fnoText(row, "expiry"));
                    if (expiry.isBefore(today) || expiry.isAfter(last))
                        continue; // never expose expired or distant dates
                    BigDecimal strike = fnoNumber(row, "strike_price", true);
                    BigDecimal tick = fnoNumber(row, "tick_size", true);
                    long lot = fnoLong(row, "lot_size");
                    if (lot < 1 || lot > 10_000 || strike.signum() <= 0
                            || tick.signum() <= 0)
                        throw new IllegalArgumentException();
                    pairs.computeIfAbsent(expiry, unused -> new HashSet<>()).add(side);
                } catch (RuntimeException invalid) {
                    throw unavailable("UPSTOX_FNO_INVALID_EXPIRY_CONTRACTS");
                }
            }
        }
        return pairs.entrySet().stream()
                .filter(e -> e.getValue().contains("CE") && e.getValue().contains("PE"))
                .map(java.util.Map.Entry::getKey).toList();
    }

    /**
     * Exchange-sourced read-only last trades from the official V3 full quote API.
     * Caller MUST verify distinct REST display rights, user ownership and expiry
     * identity before returning any value; these are never execution quotes.
     */
    public record ListedLastTrade(String instrumentKey, BigDecimal ltp,
            BigDecimal previousClose, Long openInterest,
            Instant exchangeAt, Instant observedAt) {}

    public java.util.Map<String, ListedLastTrade> listedFnoLastTrades(
            List<String> instrumentKeys) {
        if (!enabled || instrumentKeys == null || instrumentKeys.isEmpty()
                || instrumentKeys.size() > 64
                || instrumentKeys.stream().anyMatch(k ->
                    k == null || !k.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}"))
                || instrumentKeys.stream().distinct().count() != instrumentKeys.size()) {
            throw new IllegalArgumentException("UPSTOX_FNO_LAST_QUOTE_INVALID_REQUEST");
        }
        // REST is used only for last-display snapshots, NEVER PAPER or LIVE.
        var rows = get("/v3/market-quote/quotes?instrument_key={keys}",
                String.join(",", instrumentKeys)).path("data");
        if (!rows.isObject() || rows.size() > instrumentKeys.size()) {
            throw unavailable("UPSTOX_FNO_LAST_QUOTE_INVALID_DATA");
        }
        var expected = Set.copyOf(instrumentKeys);
        var mapped = new java.util.HashMap<String, ListedLastTrade>();
        Instant now = Instant.now();
        for (var values = rows.elements(); values.hasNext();) {
            var quote = values.next();
            try {
                String key = quote.path("instrument_token").asText();
                if (!expected.contains(key) || mapped.containsKey(key)) continue;
                var price = quote.path("last_price").decimalValue();
                if (price == null || price.signum() <= 0) continue;
                var tradeMs = Long.parseLong(quote.path("last_trade_time").asText());
                Instant traded = Instant.ofEpochMilli(tradeMs);
                if (traded.isAfter(now.plusSeconds(2))
                        || traded.isBefore(now.minusSeconds(7 * 86400L))) continue;
                Instant observed = java.time.OffsetDateTime.parse(
                    quote.path("timestamp").asText()).toInstant();
                if (observed.isBefore(traded) || observed.isAfter(now.plusSeconds(10))) continue;
                var previous = quote.path("prev_close_price");
                BigDecimal previousClose = previous.isNumber()
                    && previous.decimalValue().signum() > 0
                    ? previous.decimalValue() : null;
                var oiNode = quote.path("oi");
                Long oi = oiNode.isIntegralNumber() && oiNode.canConvertToLong()
                    && oiNode.longValue() >= 0 ? oiNode.longValue() : null;
                mapped.put(key, new ListedLastTrade(key, price, previousClose,
                    oi, traded, observed));
            } catch (RuntimeException invalidQuote) {
                // Drop malformed provider rows rather than fabricate a quote.
            }
        }
        return java.util.Map.copyOf(mapped);
    }

    /** Bound current contract metadata; expiry and lot size come from Upstox. */
    public List<FnoOptionContract> currentFnoOptionContracts(String underlyingKey,
                                                              LocalDate expiry) {
        validateFnoRequest(underlyingKey, expiry);
        JsonNode rows = get("/v2/option/contract?instrument_key={underlying}&expiry_date={expiry}",
                underlyingKey, expiry.toString()).path("data");
        if (!rows.isArray() || rows.size() > 1024) {
            throw unavailable("UPSTOX_FNO_INVALID_CONTRACTS");
        }
        List<FnoOptionContract> result = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        // A provider identity must match the underlying exchange all the way
        // through to the instrument key. A syntactically valid BSE_FO key is
        // not an NSE_FO contract and must never enter NSE PAPER selection.
        String expectedExchange = underlyingKey.startsWith("NSE_") ? "NSE" : "BSE";
        String expectedSegment = expectedExchange + "_FO";
        for (JsonNode row : rows) {
            try {
                if (!underlyingKey.equals(fnoText(row, "underlying_key"))
                        || !expectedExchange.equals(fnoText(row, "exchange"))
                        || !expectedSegment.equals(fnoText(row, "segment"))
                        || !expiry.equals(LocalDate.parse(fnoText(row, "expiry")))) {
                    throw new IllegalArgumentException();
                }
                String side = fnoText(row, "instrument_type");
                if (!"CE".equals(side) && !"PE".equals(side)) throw new IllegalArgumentException();
                String key = fnoContractKey(row, "instrument_key");
                if (!key.startsWith(expectedSegment + "|")) throw new IllegalArgumentException();
                BigDecimal strike = fnoNumber(row, "strike_price", true);
                BigDecimal tick = fnoOptionPriceTickInRupees(row);
                long lot = fnoLong(row, "lot_size");
                if (lot < 1 || lot > 1_000_000 || !seenKeys.add(key)) {
                    throw new IllegalArgumentException();
                }
                result.add(new FnoOptionContract(underlyingKey, key, expiry, strike,
                        side, Math.toIntExact(lot), tick, fnoText(row, "trading_symbol")));
            } catch (RuntimeException ex) {
                throw unavailable("UPSTOX_FNO_INVALID_CONTRACTS");
            }
        }
        return List.copyOf(result);
    }

    /** No cache/storage/booking/order side effects; strictly read-only Upstox API. */
    public OptionChainSnapshot currentFnoOptionChain(String underlyingKey, LocalDate expiry) {
        validateFnoRequest(underlyingKey, expiry);
        JsonNode rows = get("/v2/option/chain?instrument_key={underlying}&expiry_date={expiry}",
                underlyingKey, expiry.toString()).path("data");
        if (!rows.isArray() || rows.size() > 1024) {
            throw unavailable("UPSTOX_FNO_INVALID_CHAIN");
        }
        List<FnoOptionStrike> result = new ArrayList<>();
        Set<BigDecimal> seenStrikes = new java.util.TreeSet<>();
        for (JsonNode row : rows) {
            try {
                if (!underlyingKey.equals(fnoText(row, "underlying_key"))
                        || !expiry.equals(LocalDate.parse(fnoText(row, "expiry")))) {
                    throw new IllegalArgumentException();
                }
                BigDecimal strike = fnoNumber(row, "strike_price", true);
                BigDecimal spot = fnoNumber(row, "underlying_spot_price", true);
                if (!seenStrikes.add(strike)) throw new IllegalArgumentException();
                result.add(new FnoOptionStrike(expiry, underlyingKey, strike, spot,
                        fnoQuote(row.path("call_options")),
                        fnoQuote(row.path("put_options"))));
            } catch (RuntimeException ex) {
                throw unavailable("UPSTOX_FNO_INVALID_CHAIN");
            }
        }
        result.sort(Comparator.comparing(FnoOptionStrike::strikePrice));
        return new OptionChainSnapshot("UPSTOX_ANALYTICS", Instant.now(), false,
                List.copyOf(result));
    }

    private static void validateFnoRequest(String underlyingKey, LocalDate expiry) {
        // No user-supplied URL, HTTP method or unbounded expiry keyword reaches RestClient.
        if (underlyingKey == null || !underlyingKey.matches(
                "((NSE_INDEX|BSE_INDEX)\\|[A-Za-z0-9 ._-]{1,80}|(NSE_EQ|BSE_EQ)\\|[A-Z0-9]{12})")
                || expiry == null || expiry.isBefore(LocalDate.of(2000, 1, 1))
                || expiry.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusYears(2))) {
            throw new IllegalArgumentException("UPSTOX_FNO_INVALID_REQUEST");
        }
    }

    private static FnoOptionQuote fnoQuote(JsonNode option) {
        if (!option.isObject()) throw new IllegalArgumentException();
        JsonNode market = option.path("market_data");
        if (!market.isObject()) throw new IllegalArgumentException();
        JsonNode greeks = option.path("option_greeks");
        BigDecimal iv = fnoOptionalNumber(greeks, "iv");
        if (iv != null && iv.signum() < 0) throw new IllegalArgumentException();
        return new FnoOptionQuote(fnoContractKey(option, "instrument_key"),
                fnoNumber(market, "ltp", false),
                fnoNumber(market, "bid_price", false),
                fnoNumber(market, "ask_price", false),
                fnoLong(market, "volume"), fnoLong(market, "oi"),
                fnoLong(market, "prev_oi"),
                iv, fnoOptionalNumber(greeks, "delta"),
                fnoOptionalNumber(greeks, "gamma"),
                fnoOptionalNumber(greeks, "theta"),
                fnoOptionalNumber(greeks, "vega"));
    }

    /**
     * The live Upstox Option Contracts REST payload reports integer tick_size
     * in paise (e.g. 5 means INR 0.05), whereas the PAPER order limit and V3
     * bid/ask are in INR. Some normalized contract fixtures use decimal INR
     * ticks (0.05); preserve those. Without this conversion, legitimate
     * option prices can fail the `limit.remainder(tickSize)` check.
     *
     * Keep the source tick explicit and validated rather than guessing a
     * price increment from the current market price.
     */
    private static BigDecimal fnoOptionPriceTickInRupees(JsonNode row) {
        BigDecimal raw = fnoNumber(row, "tick_size", true);
        BigDecimal stripped = raw.stripTrailingZeros();
        BigDecimal inr = stripped.scale() <= 0 ? raw.movePointLeft(2) : raw;
        if (inr.signum() <= 0 || inr.scale() > 4)
            throw new IllegalArgumentException("UPSTOX_FNO_INVALID_TICK_SIZE");
        return inr.stripTrailingZeros();
    }

    private static String fnoContractKey(JsonNode row, String field) {
        String key = fnoText(row, field);
        if (!key.matches("(NSE_FO|BSE_FO)\\|[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException();
        }
        return key;
    }

    private static String fnoText(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (!value.isTextual() || value.asText().isBlank()
                || value.asText().length() > 128) throw new IllegalArgumentException();
        return value.asText();
    }

    private static BigDecimal fnoNumber(JsonNode row, String field, boolean positive) {
        JsonNode value = row.path(field);
        if (!value.isNumber()) throw new IllegalArgumentException();
        BigDecimal number = value.decimalValue();
        if (number.precision() > 20 || number.signum() < 0
                || (positive && number.signum() == 0)) throw new IllegalArgumentException();
        return number;
    }

    private static BigDecimal fnoOptionalNumber(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isNumber() || value.decimalValue().precision() > 20) {
            throw new IllegalArgumentException();
        }
        return value.decimalValue();
    }

    private static long fnoLong(JsonNode row, String field) {
        JsonNode value = row.path(field);
        // Upstox's published Options API models option-chain oi and prev_oi
        // as JSON number/double, unlike the integer-valued volume. Values
        // such as 4500.0 represent exact whole contracts and must not reject
        // an otherwise valid source response. Never round a fractional OI,
        // overflow a long, or turn missing/null upstream data into zero.
        if (!value.isNumber()) throw new IllegalArgumentException();
        try {
            java.math.BigDecimal number = value.decimalValue();
            if (number.signum() < 0 || number.precision() > 20)
                throw new IllegalArgumentException();
            return number.longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Invalid nonintegral F&O count");
        }
    }


    /**
     * Read-only V3 websocket authorization via existing SSM Analytics Token.
     * The returned URL carries a one-use credential: never log, cache or expose it.
     * Restricted to Upstox's TLS websocket host/path to prevent SSRF.
     */
    java.net.URI authorizeFnoMarketFeedV3() {
        JsonNode value = get("/v3/feed/market-data-feed/authorize")
                .path("data").path("authorized_redirect_uri");
        if (!value.isTextual() || value.asText().length() > 4096) {
            throw unavailable("UPSTOX_FNO_WS_AUTH_UNAVAILABLE");
        }
        try {
            java.net.URI uri = java.net.URI.create(value.asText());
            String host = uri.getHost();
            if (!"wss".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || !host.toLowerCase(java.util.Locale.ROOT).endsWith(".upstox.com")
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawQuery() == null || uri.getRawQuery().isBlank()
                    || uri.getPath() == null || !uri.getPath().startsWith("/market-data-feeder/v3/")) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException ignored) {
            // URL query contains one-use credentials; never include it in exceptions.
            throw unavailable("UPSTOX_FNO_WS_AUTH_UNAVAILABLE");
        }
    }

    private JsonNode get(String path, Object... pathVariables) {
        String token = token();
        // F&O private acceptance needs a safe failure class, not a raw response.
        // All other existing ingestion error contracts remain unchanged.
        boolean fno = path.startsWith("/v2/option/");
        try {
            // RestClient constructed without Boot auto-configured Jackson-2
            // converters: read bounded UTF-8 text, then explicitly use Jackson 2.
            String payload = client.get().uri(path, pathVariables)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(String.class);
            if (payload == null || payload.length() > MAX_RESPONSE_CHARS) {
                throw unavailable("UPSTOX_INVALID_RESPONSE");
            }
            JsonNode body = JSON.readTree(payload);
            if (body == null || !"success".equals(body.path("status").asText())
                    || body.path("data").isMissingNode()) {
                throw unavailable("UPSTOX_INVALID_RESPONSE");
            }
            return body;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            // No status text, response body, URL or headers; only a known enum.
            if (fno) {
                var status = UpstoxSourceFailureClassifier.http(
                        ex.getStatusCode().value(), null).code();
                throw unavailable("UPSTOX_FNO_" + status.name());
            }
            // Preserve HTTP 429 as a safe, machine-readable retry class for the
            // future rights-gated Golden checkpoint coordinator. Never expose
            // upstream headers, payloads, URL or tokens. No retry here.
            if (ex.getStatusCode().value() == 429) {
                throw unavailable("UPSTOX_RATE_LIMITED");
            }
            throw unavailable("UPSTOX_READ_UNAVAILABLE");
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            // Never emit the transport exception: it may embed a signed URL.
            throw unavailable(fno ? "UPSTOX_FNO_TRANSPORT_FAILURE"
                    : "UPSTOX_READ_UNAVAILABLE");
        } catch (IllegalStateException ex) {
            // Preserve safe retry/disabled classifications without exposing source data.
            if ("UPSTOX_RATE_LIMITED".equals(ex.getMessage())) throw ex;
            if (fno && "UPSTOX_INVALID_RESPONSE".equals(ex.getMessage())) {
                throw unavailable("UPSTOX_FNO_INVALID_PAYLOAD");
            }
            throw unavailable(fno ? "UPSTOX_FNO_UNKNOWN_FAILURE" : "UPSTOX_READ_UNAVAILABLE");
        } catch (Exception ex) {
            // The upstream response and token never escape this source.
            // Recognize only our own strict payload error, not arbitrary strings.
            if (fno && (ex instanceof com.fasterxml.jackson.core.JsonProcessingException
                    || (ex instanceof IllegalStateException
                    && "UPSTOX_INVALID_RESPONSE".equals(ex.getMessage())))) {
                throw unavailable("UPSTOX_FNO_INVALID_PAYLOAD");
            }
            throw unavailable(fno ? "UPSTOX_FNO_UNKNOWN_FAILURE"
                    : "UPSTOX_READ_UNAVAILABLE");
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
