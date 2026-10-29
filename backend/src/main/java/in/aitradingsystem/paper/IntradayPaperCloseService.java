package in.aitradingsystem.paper;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.domain.ProductType;
import in.aitradingsystem.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Creates idempotent SYSTEM-origin SELL intents for open INTRADAY PAPER positions after the configured cutoff.
 * Position mutation is intentionally delegated to the normal PaperTradingService risk/accounting transaction.
 */
@Service
public class IntradayPaperCloseService {
    static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final JdbcClient jdbc;
    private final PaperTradingService trading;
    private final AuditService audit;
    private final LocalTime cutoff;

    public IntradayPaperCloseService(JdbcClient jdbc, PaperTradingService trading, AuditService audit,
                                     @Value("${app.paper.intraday-close.cutoff:15:10}") String cutoff) {
        this.jdbc = jdbc;
        this.trading = trading;
        this.audit = audit;
        this.cutoff = LocalTime.parse(cutoff, DateTimeFormatter.ofPattern("HH:mm"));
    }

    public CloseRun closeEligiblePositions(Instant now) {
        if (now == null) throw new IllegalArgumentException("now is required");
        var marketNow = now.atZone(MARKET_ZONE);
        if (marketNow.toLocalTime().isBefore(cutoff)) {
            return new CloseRun(marketNow.toLocalDate(), cutoff, 0, 0, 0, List.of());
        }

        List<IntradayPosition> positions = jdbc.sql("""
                SELECT instrument_id, symbol, quantity,
                       COALESCE(
                           (SELECT mc.close_price FROM market_candle mc
                             WHERE p.instrument_id IS NOT NULL AND mc.instrument_id = p.instrument_id
                               AND mc.candle_time <= :now
                             ORDER BY mc.candle_time DESC, mc.candle_id DESC LIMIT 1),
                           average_price,
                           cost_basis / quantity
                       ) reference_price
                  FROM paper_position p
                 WHERE product_type = 'INTRADAY' AND quantity > 0
                 ORDER BY symbol, instrument_id NULLS LAST
                """)
            .param("now", java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
            .query((rs, row) -> new IntradayPosition(
                rs.getObject("instrument_id", Long.class), rs.getString("symbol"), rs.getInt("quantity"),
                rs.getBigDecimal("reference_price")))
            .list();

        int created = 0;
        int replayed = 0;
        int rejected = 0;
        List<String> orderIds = new ArrayList<>();
        LocalDate tradeDate = marketNow.toLocalDate();
        for (IntradayPosition position : positions) {
            String idempotencyKey = idempotencyKey(tradeDate, position);
            OrderCommand command = new OrderCommand(
                position.symbol(), Side.SELL, position.quantity(), position.referencePrice(),
                null, null, now, ProductType.INTRADAY);
            var result = trading.placeOrder(command, idempotencyKey);
            orderIds.add(result.order().id().toString());
            if (result.replayed()) {
                replayed++;
                continue;
            }
            if ("REJECTED".equals(result.order().status())) rejected++; else created++;
            audit.record("INTRADAY_AUTO_CLOSE", "PAPER_ORDER", result.order().id().toString(),
                Map.of(
                    "origin", "INTRADAY_AUTO_CLOSE",
                    "symbol", position.symbol(),
                    "quantity", position.quantity(),
                    "productType", ProductType.INTRADAY.name(),
                    "tradeDate", tradeDate.toString(),
                    "status", result.order().status()));
        }
        return new CloseRun(tradeDate, cutoff, positions.size(), created, replayed + rejected, List.copyOf(orderIds));
    }

    static String idempotencyKey(LocalDate tradeDate, IntradayPosition position) {
        String identity = position.instrumentId() == null ? position.symbol() : Long.toString(position.instrumentId());
        return "intraday-close:" + tradeDate + ":" + identity;
    }

    record IntradayPosition(Long instrumentId, String symbol, int quantity, BigDecimal referencePrice) {}

    public record CloseRun(LocalDate tradeDate, LocalTime cutoff, int positionsFound,
                           int filled, int replayedOrRejected, List<String> orderIds) {}
}
