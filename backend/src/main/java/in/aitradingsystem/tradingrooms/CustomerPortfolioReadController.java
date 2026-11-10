package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.provider.angelone.CustomerAngelOneController;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.upstox.CustomerUpstoxController;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only, authenticated portfolio broker adapter. Reuses existing customer
 * authorization and owner-only Angel One routing; never reads shared market
 * credentials, authorizes trading or claims broker holdings are MintTick fills.
 *
 * Missing values remain null. Response is not a consolidated net-worth ledger.
 */
@RestController
@RequestMapping("/api/customer/portfolio")
public final class CustomerPortfolioReadController {
    private static final int MAX_ROWS = 300;

    private final CustomerAngelOneController angel;
    private final CustomerUpstoxController upstox;

    public CustomerPortfolioReadController(CustomerAngelOneController angel,
                                            CustomerUpstoxController upstox) {
        this.angel = angel;
        this.upstox = upstox;
    }

    public record HoldingRow(String symbol, String name,
                             BigDecimal quantity, BigDecimal lastPrice,
                             BigDecimal marketValue, BigDecimal averagePrice,
                             BigDecimal pnl, String provenance) {}

    public record PortfolioRecords(
        String broker, String kind, Instant fetchedAt, boolean truncated,
        String attribution, List<HoldingRow> rows) {}

    /**
     * Only two explicit provider names and two record kinds are accepted.
     * Broker session and customer-identity checks are performed by the
     * same controllers as their existing protected account endpoints.
     */
    @GetMapping("/records")
    public ResponseEntity<PortfolioRecords> records(
            @RequestParam String broker, @RequestParam String kind,
            Authentication authentication) {
        if (!"ANGEL_ONE".equals(broker) && !"UPSTOX".equals(broker)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported broker");
        }
        if (!"HOLDINGS".equals(kind) && !"POSITIONS".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported record type");
        }

        JsonNode raw;
        Instant fetchedAt;
        if ("ANGEL_ONE".equals(broker)) {
            CustomerAngelOneSessionService.ConnectionStatus status =
                angel.status(authentication).getBody();
            if (status == null || !status.connected()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Broker account is not connected");
            }
            CustomerAngelOneSessionService.BrokerRead read =
                ("HOLDINGS".equals(kind)
                    ? angel.holdings(authentication)
                    : angel.positions(authentication)).getBody();
            if (read == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Broker records unavailable");
            }
            raw = read.data();
            fetchedAt = read.fetchedAt();
        } else {
            CustomerUpstoxOAuthService.Status status =
                upstox.status(authentication).getBody();
            if (status == null || !status.connected()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Broker account is not connected");
            }
            CustomerUpstoxOAuthService.AccountData read =
                ("HOLDINGS".equals(kind)
                    ? upstox.holdings(authentication)
                    : upstox.positions(authentication)).getBody();
            if (read == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Broker records unavailable");
            }
            raw = read.data();
            fetchedAt = read.fetchedAt();
        }

        JsonNode array = recordsNode(raw, kind);
        if (array == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Broker record format not verified");
        }
        boolean truncated = array.size() > MAX_ROWS;
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new PortfolioRecords(
                broker, kind, fetchedAt, truncated, "BROKER_REPORTED_UNATTRIBUTED",
                normalize(array, "POSITIONS".equals(kind))));
    }

    /** Only allowlisted account fields are reflected, never raw SmartAPI JSON. */
    static List<HoldingRow> normalize(JsonNode rows, boolean position) {
        if (rows == null || !rows.isArray()) {
            throw new IllegalArgumentException("Expected broker record array");
        }
        List<HoldingRow> result = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) continue;
            String symbol = string(row, "tradingsymbol", "trading_symbol",
                "tradingSymbol", "symbol", "instrument_key");
            if (symbol == null) continue;
            BigDecimal qty = decimal(row, "quantity", "netqty", "net_qty",
                "net_quantity", "holding_quantity");
            BigDecimal last = decimal(row, "ltp", "last_price", "lastPrice",
                "last_traded_price");
            BigDecimal value = decimal(row, "holdingvalue", "holding_value",
                "current_value", "currentValue", "market_value", "marketValue");
            if (!position && value == null && last != null && qty != null
                    && last.signum() >= 0 && qty.signum() >= 0) {
                value = last.multiply(qty).setScale(2, RoundingMode.HALF_UP);
            }
            String name = string(row, "company_name", "companyName", "name");
            result.add(new HoldingRow(
                symbol, name, qty, last, value,
                decimal(row, "averageprice", "average_price", "averagePrice",
                    "buy_avg", "buyAvg"),
                decimal(row, "pnl", "unrealised", "unrealised_pnl",
                    "unrealized_pnl", "profit_and_loss"),
                "BROKER_REPORTED"));
            if (result.size() >= MAX_ROWS) break;
        }
        return List.copyOf(result);
    }

    static JsonNode recordsNode(JsonNode node, String kind) {
        if (node == null || node.isNull()) return null;
        if (node.isArray()) return node;
        if (!node.isObject()) return null;
        String field = "POSITIONS".equals(kind) ? "positions" : "holdings";
        for (String key : new String[] {field, "data"}) {
            JsonNode child = node.get(key);
            if (child == null) continue;
            if (child.isArray()) return child;
            if (child.isObject()) {
                JsonNode nested = recordsNode(child, kind);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static String string(JsonNode row, String... fields) {
        for (String field : fields) {
            JsonNode value = row.get(field);
            if (value != null && value.isTextual()) {
                String text = value.asText().trim();
                if (!text.isBlank() && text.length() <= 120) return text;
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode row, String... fields) {
        for (String field : fields) {
            JsonNode value = row.get(field);
            if (value == null || value.isNull()
                    || !(value.isNumber() || value.isTextual())) continue;
            try {
                String source = value.asText().trim().replace(",", "");
                if (source.length() > 40) continue;
                return new BigDecimal(source);
            } catch (NumberFormatException ignored) {
                // Invalid account metric is unavailable, never treated as zero.
            }
        }
        return null;
    }
}
