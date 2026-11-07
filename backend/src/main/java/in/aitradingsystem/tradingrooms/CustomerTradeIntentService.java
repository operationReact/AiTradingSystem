package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Immutable, tenant-scoped preflight custody for both modes. All intents are
 * PRECHECK_BLOCKED. No live orders, broker requests, paper fills, capital
 * reservations or strategy actions are produced by this service.
 */
@Service
public class CustomerTradeIntentService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CustomerExecutionReadinessService readiness;

    public CustomerTradeIntentService(JdbcClient jdbc, ObjectMapper mapper,
                                      CustomerExecutionReadinessService readiness) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.readiness = readiness;
    }

    public record Intent(UUID id, UUID roomId, String mode, long instrumentId,
                         String tradingSymbol, String exchange, String side,
                         int quantity, String productType, String orderType,
                         String duration, BigDecimal limitPrice, String state,
                         List<String> blockers, Instant createdAt) {}

    @Transactional
    public Intent create(UUID owner, UUID roomId, String idempotencyKey,
                         long instrumentId, String side, int quantity,
                         String productType, BigDecimal limitPrice) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        if (!"DELIVERY".equals(productType) && !"INTRADAY".equals(productType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only DELIVERY or INTRADAY equity order intents are accepted");
        }
        var preview = readiness.preflight(owner, roomId, instrumentId, side, quantity, limitPrice);
        if (preview.executable() || preview.blockers().isEmpty()) {
            throw new IllegalStateException("Order intents must fail closed");
        }
        String hash = fingerprint(preview, productType);
        String blockersJson;
        try {
            blockersJson = mapper.writeValueAsString(preview.blockers());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not record execution blockers", e);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer_trade_intent
                    (id, room_id, user_id, mode, instrument_id, trading_symbol,
                     exchange, side, quantity, product_type, limit_price,
                     idempotency_key, payload_hash, blockers)
                VALUES (:id, :room, :owner, :mode, :instrument, :symbol,
                        :exchange, :side, :qty, :product, :limit,
                        :idempotency, :fingerprint, CAST(:blockers AS jsonb))
                ON CONFLICT (user_id,room_id,idempotency_key) DO NOTHING
                """)
            .param("id", id).param("room", roomId).param("owner", owner)
            .param("mode", preview.mode())
            .param("instrument", instrumentId)
            .param("symbol", preview.tradingSymbol())
            .param("exchange", preview.exchange())
            .param("side", side).param("qty", quantity)
            .param("product", productType).param("limit", limitPrice)
            .param("idempotency", idempotencyKey)
            .param("fingerprint", hash).param("blockers", blockersJson)
            .update();
        // The idempotency key is per owner AND room, never shared with a
        // different customer. The database uniqueness wins concurrent races.
        var stored = jdbc.sql("""
                SELECT id, room_id, mode, instrument_id, trading_symbol,
                       exchange, side, quantity, product_type, order_type,
                       duration, limit_price, state, blockers, payload_hash,
                       created_at
                FROM customer_trade_intent
                WHERE user_id = :owner AND room_id = :room
                  AND idempotency_key = :idempotency
                """)
            .param("owner", owner).param("room", roomId)
            .param("idempotency", idempotencyKey)
            .query((rs, row) -> new WithFingerprint(map(rs), rs.getString("payload_hash")))
            .single();
        if (!hash.equals(stored.fingerprint())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Idempotency-Key was already used with a different order intent");
        }
        return stored.intent();
    }

    @Transactional(readOnly = true)
    public List<Intent> list(UUID owner, UUID roomId) {
        // Fail closed for other customers, even if no intents exist.
        readiness.readiness(owner, roomId);
        return jdbc.sql("""
                SELECT id, room_id, mode, instrument_id, trading_symbol,
                       exchange, side, quantity, product_type, order_type,
                       duration, limit_price, state, blockers, created_at
                FROM customer_trade_intent
                WHERE user_id = :owner AND room_id = :room
                ORDER BY created_at DESC, id DESC LIMIT 100
                """)
            .param("owner", owner).param("room", roomId)
            .query((rs, row) -> map(rs))
            .list();
    }

    private record WithFingerprint(Intent intent, String fingerprint) {}

    private Intent map(ResultSet rs) throws SQLException {
        List<String> blockers;
        try {
            var json = mapper.readTree(rs.getString("blockers"));
            if (json == null || !json.isArray()) {
                throw new IllegalStateException("Invalid stored execution blockers");
            }
            var result = new java.util.ArrayList<String>();
            for (var item : json) {
                if (!item.isTextual()) throw new IllegalStateException("Invalid blocker");
                result.add(item.asText());
            }
            blockers = List.copyOf(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Invalid trade intent blocker JSON", ex);
        }
        return new Intent(
            rs.getObject("id", UUID.class),
            rs.getObject("room_id", UUID.class),
            rs.getString("mode"),
            rs.getLong("instrument_id"),
            rs.getString("trading_symbol"), rs.getString("exchange"),
            rs.getString("side"), rs.getInt("quantity"),
            rs.getString("product_type"),
            rs.getString("order_type"), rs.getString("duration"),
            rs.getBigDecimal("limit_price"), rs.getString("state"),
            blockers, rs.getTimestamp("created_at").toInstant());
    }

    private static String fingerprint(CustomerExecutionReadinessService.Preflight order,
                                      String productType) {
        String canonical = String.join("|", order.roomId().toString(), order.mode(),
            String.valueOf(order.instrumentId()), order.tradingSymbol(),
            order.exchange(), order.side(), String.valueOf(order.quantity()),
            productType, order.orderType(), order.duration(),
            order.limitPrice().stripTrailingZeros().toPlainString());
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 required", ex);
        }
    }
}
