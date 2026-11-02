package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.instrument.Instrument;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Strict read-only conversion of SmartAPI FULL quotes into immutable research depth/spread evidence.
 * This service never submits, modifies or cancels orders and its output cannot authorize capital.
 */
@Service
public class AngelOneMarketDepthService {
    public static final String CONTRACT_VERSION = "angel-one-market-depth.v1";
    public static final String SOURCE = "ANGEL_ONE_SMARTAPI_FULL_QUOTE";

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FEED_TIME =
            DateTimeFormatter.ofPattern("dd-MMM-uuuu HH:mm:ss", Locale.ENGLISH);
    private static final MathContext PRICE_MATH = MathContext.DECIMAL64;

    public record DepthEvidence(
            long instrumentId,
            String catalogueVersion,
            String exchange,
            String tradingSymbol,
            String symbolToken,
            Instant eventAt,
            Instant availableAt,
            BigDecimal ltp,
            BigDecimal bestBidPrice,
            long bestBidQuantity,
            long bestBidOrders,
            BigDecimal bestAskPrice,
            long bestAskQuantity,
            long bestAskOrders,
            BigDecimal midpoint,
            BigDecimal spreadBps,
            String source,
            String sourceId) {}

    public record MarketDepthResearchExport(
            String contractVersion,
            Instant generatedAt,
            Instant dataCutoff,
            int rowCount,
            List<DepthEvidence> evidence,
            String status,
            boolean canAuthorizeCapital) {}

    private final AngelOneSessionManager session;
    private final AngelOneApiClient api;
    private final AngelOneRateLimiter rateLimiter;
    private final AngelOneHealthService health;
    private final AngelOneRetryPolicy retryPolicy;
    private final Clock clock;

    @Autowired
    public AngelOneMarketDepthService(AngelOneSessionManager session, AngelOneApiClient api,
                                      AngelOneRateLimiter rateLimiter, AngelOneHealthService health,
                                      AngelOneRetryPolicy retryPolicy) {
        this(session, api, rateLimiter, health, retryPolicy, Clock.systemUTC());
    }

    AngelOneMarketDepthService(AngelOneSessionManager session, AngelOneApiClient api,
                               AngelOneRateLimiter rateLimiter, AngelOneHealthService health,
                               AngelOneRetryPolicy retryPolicy, Clock clock) {
        this.session = session;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.health = health;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    public MarketDepthResearchExport fetch(List<Instrument> instruments) {
        List<Instrument> requested = validateInstruments(instruments);
        List<String> tokens = requested.stream().map(Instrument::symbolToken).toList();
        String usedToken = null;
        try {
            usedToken = session.accessToken();
            JsonNode data = readWithPolicy(usedToken, tokens);
            MarketDepthResearchExport result = parse(data, requested, clock.instant());
            health.recordQuoteSuccess();
            return result;
        } catch (AngelOneException ex) {
            if (ex.kind() == AngelOneException.Kind.SESSION_EXPIRED && usedToken != null) {
                return retryOnce(requested, tokens, usedToken);
            }
            health.recordFailure(ex);
            throw ex;
        }
    }

    private MarketDepthResearchExport retryOnce(List<Instrument> instruments, List<String> tokens,
                                                String failedToken) {
        try {
            rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.MARKET_QUOTE);
            String retryToken = session.recoverAfterSessionExpiry(failedToken);
            JsonNode data;
            if (retryPolicy == null) {
                data = api.fullMarketQuote(retryToken, tokens);
            } else {
                data = retryPolicy.executeSafeRead(
                        AngelOneApiClient.MARKET_QUOTE_PATH,
                        () -> api.fullMarketQuote(retryToken, tokens),
                        () -> {
                            rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.MARKET_QUOTE);
                            return api.fullMarketQuote(retryToken, tokens);
                        });
            }
            MarketDepthResearchExport result = parse(data, instruments, clock.instant());
            health.recordQuoteSuccess();
            return result;
        } catch (AngelOneException ex) {
            health.recordFailure(ex);
            throw ex;
        }
    }

    private JsonNode readWithPolicy(String token, List<String> symbolTokens) {
        if (retryPolicy == null) {
            rateLimiter.acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
            return api.fullMarketQuote(token, symbolTokens);
        }
        return retryPolicy.executeSafeRead(
                AngelOneApiClient.MARKET_QUOTE_PATH,
                () -> {
                    rateLimiter.acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
                    return api.fullMarketQuote(token, symbolTokens);
                },
                () -> {
                    rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.MARKET_QUOTE);
                    return api.fullMarketQuote(token, symbolTokens);
                });
    }

    private List<Instrument> validateInstruments(List<Instrument> instruments) {
        if (instruments == null || instruments.isEmpty() || instruments.size() > 50) {
            throw new IllegalArgumentException("market-depth research requires between 1 and 50 instruments");
        }
        List<Instrument> ordered = new ArrayList<>(instruments);
        ordered.sort(Comparator.comparingLong(Instrument::id));
        Set<Long> ids = new HashSet<>();
        Set<String> tokens = new HashSet<>();
        Set<String> tradingSymbols = new HashSet<>();
        for (Instrument instrument : ordered) {
            if (instrument == null || !"NSE".equals(instrument.exchange()) || !"EQ".equals(instrument.instrumentType())) {
                throw new IllegalArgumentException("market-depth research accepts only active NSE cash-equity instruments");
            }
            if (!ids.add(instrument.id()) || !tokens.add(instrument.symbolToken())
                    || !tradingSymbols.add(instrument.tradingSymbol())) {
                throw new IllegalArgumentException("market-depth research instruments must have unique identities");
            }
        }
        return List.copyOf(ordered);
    }

    private MarketDepthResearchExport parse(JsonNode data, List<Instrument> instruments, Instant availableAt) {
        JsonNode fetched = data.get("fetched");
        JsonNode unfetched = data.get("unfetched");
        if (fetched == null || !fetched.isArray() || unfetched == null || !unfetched.isArray()) {
            throw invalid("MARKET_QUOTE_SHAPE", "FULL market quote response did not contain fetched/unfetched arrays");
        }
        if (!unfetched.isEmpty()) {
            throw invalid("MARKET_QUOTE_UNFETCHED", "FULL market quote did not fetch every requested instrument");
        }
        if (fetched.size() != instruments.size()) {
            throw invalid("MARKET_QUOTE_COUNT_MISMATCH", "FULL market quote result count did not match the request");
        }

        Map<String, Instrument> expectedByToken = new HashMap<>();
        for (Instrument instrument : instruments) expectedByToken.put(instrument.symbolToken(), instrument);
        Set<String> seenTokens = new HashSet<>();
        List<DepthEvidence> evidence = new ArrayList<>(instruments.size());
        for (JsonNode quote : fetched) {
            if (!quote.isObject()) {
                throw invalid("MARKET_QUOTE_ROW_NOT_OBJECT", "FULL market quote contained a non-object row");
            }
            String exchange = requiredText(quote, "exchange");
            String tradingSymbol = requiredText(quote, "tradingSymbol");
            String symbolToken = requiredText(quote, "symbolToken");
            Instrument instrument = expectedByToken.get(symbolToken);
            if (instrument == null || !seenTokens.add(symbolToken)) {
                throw invalid("MARKET_QUOTE_IDENTITY_MISMATCH", "FULL market quote returned an unknown or duplicate token");
            }
            if (!instrument.exchange().equals(exchange) || !instrument.tradingSymbol().equals(tradingSymbol)) {
                throw invalid("MARKET_QUOTE_IDENTITY_MISMATCH", "FULL market quote identity did not match the catalogue");
            }

            Instant eventAt = parseFeedTime(requiredText(quote, "exchFeedTime"));
            if (eventAt.isAfter(availableAt)) {
                throw invalid("MARKET_QUOTE_FUTURE_EVENT", "FULL market quote event time exceeded fetch availability");
            }
            BigDecimal ltp = positiveDecimal(quote, "ltp");
            JsonNode depth = quote.get("depth");
            if (depth == null || !depth.isObject()) {
                throw invalid("MARKET_QUOTE_DEPTH_MISSING", "FULL market quote did not contain depth");
            }
            JsonNode buy = depth.get("buy");
            JsonNode sell = depth.get("sell");
            if (buy == null || !buy.isArray() || buy.isEmpty() || sell == null || !sell.isArray() || sell.isEmpty()) {
                throw invalid("MARKET_QUOTE_TOP_OF_BOOK_MISSING", "FULL market quote did not contain best bid and ask");
            }
            JsonNode bestBid = buy.get(0);
            JsonNode bestAsk = sell.get(0);
            BigDecimal bid = positiveDecimal(bestBid, "price");
            BigDecimal ask = positiveDecimal(bestAsk, "price");
            if (ask.compareTo(bid) < 0) {
                throw invalid("MARKET_QUOTE_CROSSED_BOOK", "FULL market quote best ask was below best bid");
            }
            long bidQuantity = nonNegativeLong(bestBid, "quantity");
            long askQuantity = nonNegativeLong(bestAsk, "quantity");
            long bidOrders = nonNegativeLong(bestBid, "orders");
            long askOrders = nonNegativeLong(bestAsk, "orders");
            BigDecimal midpoint = bid.add(ask).divide(BigDecimal.valueOf(2), PRICE_MATH);
            BigDecimal spreadBps = ask.subtract(bid)
                    .divide(midpoint, PRICE_MATH)
                    .multiply(BigDecimal.valueOf(10_000), PRICE_MATH)
                    .setScale(6, RoundingMode.HALF_UP);
            String sourceId = fingerprint(instrument, eventAt, ltp, bid, bidQuantity, bidOrders,
                    ask, askQuantity, askOrders, midpoint, spreadBps);
            evidence.add(new DepthEvidence(
                    instrument.id(),
                    instrument.catalogueVersion().toString(),
                    exchange,
                    tradingSymbol,
                    symbolToken,
                    eventAt,
                    availableAt,
                    ltp,
                    bid,
                    bidQuantity,
                    bidOrders,
                    ask,
                    askQuantity,
                    askOrders,
                    midpoint,
                    spreadBps,
                    SOURCE,
                    sourceId));
        }
        if (seenTokens.size() != expectedByToken.size()) {
            throw invalid("MARKET_QUOTE_MISSING_TOKEN", "FULL market quote omitted a requested instrument");
        }
        evidence.sort(Comparator.comparingLong(DepthEvidence::instrumentId));
        return new MarketDepthResearchExport(
                CONTRACT_VERSION,
                availableAt,
                availableAt,
                evidence.size(),
                List.copyOf(evidence),
                "RESEARCH_MARKET_DEPTH_ONLY",
                false);
    }

    private Instant parseFeedTime(String value) {
        try {
            return LocalDateTime.parse(value, FEED_TIME).atZone(MARKET_ZONE).toInstant();
        } catch (DateTimeParseException ex) {
            throw invalid("MARKET_QUOTE_FEED_TIME_INVALID", "FULL market quote exchange feed time was invalid");
        }
    }

    private BigDecimal positiveDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw invalid("MARKET_QUOTE_NUMERIC_FIELD_INVALID", "FULL market quote contained an invalid numeric field");
        }
        BigDecimal result = value.decimalValue();
        if (result.signum() <= 0) {
            throw invalid("MARKET_QUOTE_PRICE_NON_POSITIVE", "FULL market quote contained a non-positive price");
        }
        return result;
    }

    private long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid("MARKET_QUOTE_DEPTH_FIELD_INVALID", "FULL market quote contained an invalid depth field");
        }
        return value.longValue();
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("MARKET_QUOTE_TEXT_FIELD_INVALID", "FULL market quote contained an invalid text field");
        }
        return value.asText();
    }

    private String fingerprint(Instrument instrument, Instant eventAt, BigDecimal ltp,
                               BigDecimal bid, long bidQuantity, long bidOrders,
                               BigDecimal ask, long askQuantity, long askOrders,
                               BigDecimal midpoint, BigDecimal spreadBps) {
        String canonical = String.join("\u001f",
                CONTRACT_VERSION,
                instrument.catalogueVersion().toString(),
                Long.toString(instrument.id()),
                instrument.exchange(),
                instrument.tradingSymbol(),
                instrument.symbolToken(),
                eventAt.toString(),
                ltp.toPlainString(),
                bid.toPlainString(),
                Long.toString(bidQuantity),
                Long.toString(bidOrders),
                ask.toPlainString(),
                Long.toString(askQuantity),
                Long.toString(askOrders),
                midpoint.toPlainString(),
                spreadBps.toPlainString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private AngelOneException invalid(String code, String message) {
        return new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, code, message);
    }
}
