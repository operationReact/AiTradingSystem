package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.MarketLastTickStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
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
 * Explicit user-requested, immediate-fill CASH EQUITY Paper simulator only.
 * Uses the latest VERIFIED LIVE exchange tick with strict freshness/session
 * controls. Never submits to a broker and never references LIVE balances.
 *
 * This is a deliberately small manual-first engine; Mind/strategy automation
 * and real-money order execution have separate non-bypassable approval gates.
 */
@Service
public class CustomerPaperOrderService {
    private static final Duration MAX_TICK_AGE = Duration.ofSeconds(15);
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private final JdbcClient jdbc;
    private final InstrumentMasterService instruments;
    private final MarketLastTickStore ticks;
    private final MarketSessionCalendar marketCalendar;
    private final Clock clock;

    public CustomerPaperOrderService(JdbcClient jdbc, InstrumentMasterService instruments,
            MarketLastTickStore ticks, MarketSessionCalendar marketCalendar) {
        this(jdbc, instruments, ticks, marketCalendar, Clock.systemUTC());
    }
    CustomerPaperOrderService(JdbcClient jdbc, InstrumentMasterService instruments,
            MarketLastTickStore ticks, MarketSessionCalendar marketCalendar, Clock clock) {
        this.jdbc = jdbc;
        this.instruments = instruments;
        this.ticks = ticks;
        this.marketCalendar = marketCalendar;
        this.clock = clock;
    }

    public record Receipt(UUID id, UUID roomId, long instrumentId, String exchange,
            String tradingSymbol, String side, int quantity, BigDecimal limitPrice,
            BigDecimal fillPrice, long notionalPaise, long feesPaise,
            long realizedPnlDeltaPaise, long cashAfterPaise,
            Instant exchangeAt, Instant createdAt) {}
    public record Position(long instrumentId, int quantity, long avgCostPaise) {}
    private record Wallet(long initialCash, long cash, long realizedPnl, boolean halted) {}

    @Transactional
    public Receipt submit(UUID owner, UUID roomId, String key, long instrumentId,
            String side, int qty, BigDecimal limit) {
        if (owner == null || roomId == null || key == null
                || !key.matches("[A-Za-z0-9_-]{8,64}") || instrumentId <= 0
                || !("BUY".equals(side) || "SELL".equals(side))
                || qty < 1 || qty > 10000 || limit == null
                || limit.signum() <= 0 || limit.scale() > 4
                || limit.compareTo(new BigDecimal("1000000")) > 0) {
            throw reject(HttpStatus.BAD_REQUEST, "Invalid Paper order");
        }
        Instrument instrument = instruments.requireActiveInstrument(instrumentId);
        if (!(("NSE".equals(instrument.exchange()) || "BSE".equals(instrument.exchange()))
                && ("EQ".equalsIgnoreCase(instrument.instrumentType())
                    || "EQUITY".equalsIgnoreCase(instrument.instrumentType())
                    || "STOCK".equalsIgnoreCase(instrument.instrumentType())))) {
            throw reject(HttpStatus.UNPROCESSABLE_ENTITY, "Cash equities only");
        }
        if (instrument.tickSize() != null && instrument.tickSize().signum() > 0
                && limit.remainder(instrument.tickSize()).signum() != 0) {
            throw reject(HttpStatus.BAD_REQUEST, "Limit must be a valid tick-size multiple");
        }
        String fingerprint = hash(instrumentId+"|"+side+"|"+qty+"|"+
                limit.stripTrailingZeros().toPlainString());
        // Owner + wallet lock serializes all writes for the same Paper Room,
        // including retries with the same idempotency key.
        Wallet wallet = jdbc.sql("""
                SELECT a.initial_cash_paise, a.available_cash_paise,
                       a.realized_pnl_paise, a.halted
                FROM customer_paper_account a
                JOIN customer_trading_room r
                  ON r.id = a.room_id AND r.user_id = a.user_id
                WHERE a.room_id = :room AND a.user_id = :owner
                  AND r.mode = 'PAPER' AND r.status = 'PAPER_FUNDED'
                FOR UPDATE OF a
                """)
            .param("room", roomId).param("owner", owner)
            .query((rs, row) -> new Wallet(rs.getLong("initial_cash_paise"),
                rs.getLong("available_cash_paise"), rs.getLong("realized_pnl_paise"),
                rs.getBoolean("halted")))
            .optional().orElseThrow(() -> reject(HttpStatus.NOT_FOUND,
                "Funded Paper Room not found"));
        Receipt prior = getByKey(owner, roomId, key);
        if (prior != null) {
            String priorHash = jdbc.sql("""
                    SELECT payload_hash FROM customer_paper_manual_order
                    WHERE user_id=:owner AND room_id=:room AND idempotency_key=:key
                    """).param("owner", owner).param("room", roomId).param("key", key)
                    .query(String.class).single();
            if (!priorHash.equals(fingerprint)) {
                throw reject(HttpStatus.CONFLICT, "Idempotency key already used for another order");
            }
            return prior;
        }
        if (wallet.halted()) throw reject(HttpStatus.CONFLICT, "Paper Room is paused");
        Instant now = clock.instant();
        if (!marketCalendar.isInsideCashSession(now)) {
            throw reject(HttpStatus.CONFLICT, "Market session is closed for Paper fills");
        }
        MarketLastTickStore.LastTick tick = ticks.lastFor(List.of(instrumentId)).get(instrumentId);
        if (tick == null || tick.ltp() == null || tick.ltp().signum() <= 0
                || tick.exchangeAt() == null
                || !marketCalendar.isInsideCashSession(tick.exchangeAt())
                || tick.exchangeAt().isAfter(now.plusSeconds(2))
                || Duration.between(tick.exchangeAt(), now).compareTo(MAX_TICK_AGE) > 0) {
            throw reject(HttpStatus.CONFLICT, "Fresh verified broker quote unavailable");
        }
        if (!crossesLimit(side, tick.ltp(), limit)) {
            throw reject(HttpStatus.CONFLICT, "Limit order is not marketable at verified LTP");
        }
        long priceEachPaise = toPaise(tick.ltp());
        long notional = toPaise(tick.ltp().multiply(BigDecimal.valueOf(qty)));
        long fees = simulatedFees(notional);
        // Hard single-order exposure cap: 20% of the starting virtual wallet
        // (and no leverage). No client-provided limit can raise this cap.
        if (notional > Math.max(100_000L, wallet.initialCash() / 5)) {
            throw reject(HttpStatus.CONFLICT, "Paper per-order exposure limit exceeded");
        }
        Position position = jdbc.sql("""
                SELECT instrument_id,quantity,avg_cost_paise
                FROM customer_paper_manual_position
                WHERE room_id=:room AND user_id=:owner AND instrument_id=:instrument
                FOR UPDATE
                """)
            .param("room", roomId).param("owner", owner)
            .param("instrument", instrumentId)
            .query((rs, row) -> new Position(rs.getLong("instrument_id"),
                rs.getInt("quantity"), rs.getLong("avg_cost_paise")))
            .optional().orElse(new Position(instrumentId, 0, 0));
        long deltaCash;
        long realized = 0;
        int newQty;
        long newAvg;
        if ("BUY".equals(side)) {
            long gross = Math.addExact(notional, fees);
            if (gross > wallet.cash()) {
                throw reject(HttpStatus.CONFLICT, "Insufficient Paper cash");
            }
            deltaCash = -gross;
            newQty = Math.addExact(position.quantity(), qty);
            long previousCost = Math.multiplyExact(position.avgCostPaise(), position.quantity());
            newAvg = Math.addExact(previousCost, gross) / newQty;
        } else {
            if (qty > position.quantity()) {
                throw reject(HttpStatus.CONFLICT, "Insufficient Paper shares");
            }
            deltaCash = Math.subtractExact(notional, fees);
            if (deltaCash <= 0) {
                throw reject(HttpStatus.CONFLICT, "Paper fees exceed proceeds");
            }
            newQty = position.quantity() - qty;
            newAvg = newQty == 0 ? 0L : position.avgCostPaise();
            realized = Math.subtractExact(deltaCash,
                    Math.multiplyExact(position.avgCostPaise(), qty));
        }
        long newCash = Math.addExact(wallet.cash(), deltaCash);
        if (newCash < 0) throw reject(HttpStatus.CONFLICT, "Insufficient virtual funds");
        jdbc.sql("""
                INSERT INTO customer_paper_manual_position
                    (room_id,user_id,instrument_id,quantity,avg_cost_paise)
                VALUES (:room,:owner,:instrument,:qty,:avg)
                ON CONFLICT (room_id,instrument_id) DO UPDATE SET
                    quantity=EXCLUDED.quantity,
                    avg_cost_paise=EXCLUDED.avg_cost_paise,
                    updated_at=NOW()
                WHERE customer_paper_manual_position.user_id=EXCLUDED.user_id
                """)
            .param("room", roomId).param("owner", owner)
            .param("instrument", instrumentId).param("qty", newQty)
            .param("avg", newAvg).update();
        jdbc.sql("""
                UPDATE customer_paper_account SET
                    available_cash_paise=:cash,
                    realized_pnl_paise=realized_pnl_paise + :realized,
                    ledger_version=ledger_version + 1, updated_at=NOW()
                WHERE room_id=:room AND user_id=:owner AND halted=FALSE
                """)
            .param("cash", newCash).param("realized", realized)
            .param("room", roomId).param("owner", owner).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer_paper_manual_order
                    (id,room_id,user_id,instrument_id,exchange,trading_symbol,
                     side,quantity,limit_price,fill_price,notional_paise,
                     fees_paise,realized_pnl_delta_paise,cash_after_paise,
                     exchange_at,idempotency_key,payload_hash)
                VALUES (:id,:room,:owner,:instrument,:exchange,:symbol,
                    :side,:qty,:limit,:fill,:notional,:fees,:realized,:cash,
                    :exchangeAt,:key,:fingerprint)
                """)
            .param("id", id).param("room", roomId).param("owner", owner)
            .param("instrument", instrumentId).param("exchange", instrument.exchange())
            .param("symbol", instrument.tradingSymbol())
            .param("side", side).param("qty", qty).param("limit", limit)
            .param("fill", tick.ltp()).param("notional", notional)
            .param("fees", fees).param("realized", realized).param("cash", newCash)
            .param("exchangeAt", tick.exchangeAt())
            .param("key", key).param("fingerprint", fingerprint).update();
        return getByKey(owner, roomId, key);
    }

    @Transactional(readOnly = true)
    public List<Receipt> orders(UUID owner, UUID roomId) {
        requirePaperRoom(owner, roomId);
        return jdbc.sql("""
                SELECT id,room_id,instrument_id,exchange,trading_symbol,side,
                    quantity,limit_price,fill_price,notional_paise,fees_paise,
                    realized_pnl_delta_paise,cash_after_paise,exchange_at,created_at
                FROM customer_paper_manual_order
                WHERE room_id=:room AND user_id=:owner
                ORDER BY created_at DESC,id DESC LIMIT 100
                """).param("room", roomId).param("owner", owner)
                .query((rs, row) -> map(rs)).list();
    }

    @Transactional(readOnly = true)
    public List<Position> positions(UUID owner, UUID roomId) {
        requirePaperRoom(owner, roomId);
        return jdbc.sql("""
                SELECT instrument_id,quantity,avg_cost_paise
                FROM customer_paper_manual_position
                WHERE room_id=:room AND user_id=:owner AND quantity>0
                ORDER BY instrument_id LIMIT 300
                """).param("room", roomId).param("owner", owner)
                .query((rs, row) -> new Position(rs.getLong("instrument_id"),
                    rs.getInt("quantity"), rs.getLong("avg_cost_paise"))).list();
    }

    private void requirePaperRoom(UUID owner, UUID roomId) {
        Boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM customer_trading_room
                    WHERE id=:room AND user_id=:owner AND mode='PAPER'
                      AND status='PAPER_FUNDED')
                """).param("room", roomId).param("owner", owner)
                .query(Boolean.class).single();
        if (!Boolean.TRUE.equals(exists)) throw reject(HttpStatus.NOT_FOUND, "Paper Room unavailable");
    }

    private Receipt getByKey(UUID owner, UUID roomId, String key) {
        return jdbc.sql("""
                SELECT id,room_id,instrument_id,exchange,trading_symbol,side,
                    quantity,limit_price,fill_price,notional_paise,fees_paise,
                    realized_pnl_delta_paise,cash_after_paise,exchange_at,created_at
                FROM customer_paper_manual_order
                WHERE user_id=:owner AND room_id=:room AND idempotency_key=:key
                """).param("owner", owner).param("room", roomId).param("key", key)
                .query((rs, row) -> map(rs)).optional().orElse(null);
    }

    private static Receipt map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Receipt(rs.getObject("id", UUID.class),rs.getObject("room_id",UUID.class),
            rs.getLong("instrument_id"),rs.getString("exchange"),
            rs.getString("trading_symbol"),rs.getString("side"),rs.getInt("quantity"),
            rs.getBigDecimal("limit_price"),rs.getBigDecimal("fill_price"),
            rs.getLong("notional_paise"),rs.getLong("fees_paise"),
            rs.getLong("realized_pnl_delta_paise"),rs.getLong("cash_after_paise"),
            rs.getTimestamp("exchange_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());
    }

    static boolean crossesLimit(String side, BigDecimal quote, BigDecimal limit) {
        return "BUY".equals(side) ? quote.compareTo(limit) <= 0
            : "SELL".equals(side) && quote.compareTo(limit) >= 0;
    }
    static long toPaise(BigDecimal amount) {
        return amount.multiply(ONE_HUNDRED).setScale(0,RoundingMode.HALF_UP)
            .longValueExact();
    }
    static long simulatedFees(long grossPaise) {
        return Math.max(100L, BigDecimal.valueOf(grossPaise)
            .multiply(new BigDecimal("0.0005"))
            .setScale(0,RoundingMode.CEILING).longValueExact());
    }
    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256 required");
        }
    }
    private static ResponseStatusException reject(HttpStatus status, String msg) {
        return new ResponseStatusException(status,msg);
    }
}
