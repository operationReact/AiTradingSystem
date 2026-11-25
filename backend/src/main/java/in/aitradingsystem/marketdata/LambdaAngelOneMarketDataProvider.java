package in.aitradingsystem.marketdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.instrument.Instrument;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Read-only market-data bridge. IAM permits invoking a dedicated Lambda, not reading broker SSM secrets.
 */
@Component
@ConditionalOnProperty(prefix = "app.market-data", name = "provider", havingValue = "ANGEL_ONE_LAMBDA")
public class LambdaAngelOneMarketDataProvider implements MarketDataProvider {

    /** Stable reason codes the market-feed Lambda returns that the backend may report as-is. */
    static final java.util.Set<String> PASSTHROUGH_CODES = java.util.Set.of(
            "PROVIDER_REJECTED", "PROVIDER_INVALID_RESPONSE", "INTERNAL_FAILURE", "PROVIDER_INVALID_REQUEST");
    private final LambdaClient lambda;
    private final ObjectMapper mapper;
    private final HistoricalCandleParser parser;
    private final String functionName;

    public LambdaAngelOneMarketDataProvider(LambdaClient lambda, ObjectMapper mapper,
            HistoricalCandleParser parser,
            @Value("${app.market-data.lambda-function-name:}") String functionName) {
        if (functionName == null || !functionName.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Market-data Lambda function name must be configured");
        }
        this.lambda = lambda;
        this.mapper = mapper;
        this.parser = parser;
        this.functionName = functionName;
    }

    @Override
    public String source() {
        return "ANGEL_ONE_SMARTAPI";
    }

    @Override
    public BigDecimal lastTradedPrice(Instrument instrument) {
        JsonNode data = invoke(Map.of("operation", "quote",
                "exchange", instrument.exchange(),
                "tradingSymbol", instrument.tradingSymbol(),
                "symbolToken", instrument.symbolToken()));
        JsonNode ltp = data.get("ltp");
        if (ltp == null || !(ltp.isTextual() || ltp.isNumber())) throw invalidResponse();
        try {
            BigDecimal value = new BigDecimal(ltp.asText());
            if (value.signum() <= 0) throw invalidResponse();
            return value;
        } catch (NumberFormatException ex) {
            throw invalidResponse();
        }
    }

    @Override
    public List<HistoricalCandleParser.CandleValues> historicalCandles(
            String exchange, String symbolToken, String interval, String fromDate, String toDate) {
        JsonNode data = invoke(Map.of("operation", "candles",
                "exchange", exchange, "symbolToken", symbolToken,
                "interval", interval, "fromDate", fromDate, "toDate", toDate));
        JsonNode rows = data.get("candles");
        if (rows == null || !rows.isArray()) throw invalidResponse();
        return parser.parse(rows);
    }

    /** Secret-free invoke failure code from the exception CLASS only, never the message. */
    static String invokeCode(software.amazon.awssdk.services.lambda.model.LambdaException ex) {
        String name = ex.getClass().getSimpleName();
        if (name.contains("AccessDenied")) return "PROVIDER_INVOKE_ACCESS_DENIED";
        if (name.contains("ResourceNotFound")) return "PROVIDER_INVOKE_NOT_FOUND";
        if (name.contains("TooManyRequests") || name.contains("Throttl")) return "PROVIDER_INVOKE_THROTTLED";
        if (name.contains("InvalidParameter")) return "PROVIDER_INVOKE_INVALID_REQUEST";
        return "PROVIDER_INVOKE_FAILED";
    }

    private JsonNode invoke(Map<String, String> request) {
        final byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(request);
        } catch (JsonProcessingException ex) {
            throw new MarketDataException("PROVIDER_INVALID_REQUEST", "Invalid market quote request");
        }
        final InvokeResponse response;
        try {
            response = lambda.invoke(InvokeRequest.builder().functionName(functionName)
                    .invocationType(InvocationType.REQUEST_RESPONSE)
                    .payload(SdkBytes.fromByteArray(payload)).build());
        } catch (software.amazon.awssdk.services.lambda.model.LambdaException ex) {
            throw new MarketDataException(invokeCode(ex), "Market-data provider invoke failed");
        } catch (SdkException ex) {
            throw new MarketDataException("PROVIDER_INVOKE_FAILED", "Market-data provider invoke failed");
        }
        if (response.statusCode() == null || response.statusCode() != 200) {
            throw new MarketDataException("PROVIDER_INVOKE_STATUS", "Market-data provider invoke returned a status");
        }
        if (response.functionError() != null) {
            throw new MarketDataException("PROVIDER_FUNCTION_ERROR", "Market-data provider function failed");
        }
        if (response.payload() == null) {
            throw new MarketDataException("PROVIDER_INVALID_RESPONSE", "Market-data provider returned no payload");
        }
        try {
            JsonNode root = mapper.readTree(response.payload().asByteArray());
            if (root == null || !root.isObject()) throw invalidResponse();
            if (!root.path("ok").asBoolean(false)) {
                String code = root.path("code").asText("");
                if ("PROVIDER_RATE_LIMITED".equals(code)) {
                    throw new MarketDataException("PROVIDER_RATE_LIMITED", "Market-data rate limit reached");
                }
                if ("CREDENTIALS_NOT_CONFIGURED".equals(code)
                        || "INVALID_TOTP_CONFIGURATION".equals(code)
                        || "SSM_UNAVAILABLE".equals(code)) {
                    throw new MarketDataException("PROVIDER_NOT_CONFIGURED", "Market-data configuration is unavailable");
                }
                // Known, secret-free Lambda reason codes are kept so the operator can see the failing layer.
                // Anything unrecognised stays the generic code. The Lambda's messages are never propagated.
                if (PASSTHROUGH_CODES.contains(code)) {
                    throw new MarketDataException(code, "Market-data provider reported " + code);
                }
                throw new MarketDataException("PROVIDER_UNAVAILABLE", "Market-data provider is unavailable");
            }
            if (!source().equals(root.path("source").asText())) throw invalidResponse();
            return root;
        } catch (IOException ex) {
            throw invalidResponse();
        }
    }

    private MarketDataException invalidResponse() {
        return new MarketDataException("PROVIDER_INVALID_RESPONSE",
                "Market-data provider returned an invalid response");
    }
}
