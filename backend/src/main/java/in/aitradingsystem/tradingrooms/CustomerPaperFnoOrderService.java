package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Strict long-NSE-index-option PAPER fills; no broker orders, shorts or futures. */
@Service
@ConditionalOnProperty(name="minttick.upstox-fno.paper-enabled", havingValue="true")
public class CustomerPaperFnoOrderService {
    private static final String UNDERLYING = "NSE_INDEX|Nifty 50";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private final JdbcClient jdbc;
    private final UpstoxAnalyticsSource source;
    private final UpstoxFnoV3LiveFeedService live;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean rightsVerified;

    @Autowired
    public CustomerPaperFnoOrderService(JdbcClient jdbc,
            UpstoxAnalyticsSource source, UpstoxFnoV3LiveFeedService live,
            MarketSessionCalendar calendar,
            @Value("${minttick.upstox-fno.paper-usage-rights-verified:false}") boolean rightsVerified) {
        this(jdbc, source, live, calendar, Clock.systemUTC(), rightsVerified);
    }

    CustomerPaperFnoOrderService(JdbcClient jdbc, UpstoxAnalyticsSource source,
            UpstoxFnoV3LiveFeedService live, MarketSessionCalendar calendar,
            Clock clock, boolean rightsVerified) {
        this.jdbc=jdbc; this.source=source; this.live=live;
        this.calendar=calendar; this.clock=clock; this.rightsVerified=rightsVerified;
    }

    public record Fill(UUID id, UUID roomId, String instrumentKey, String optionType,
            LocalDate expiry, String side, int quantity, int lotSize,
            BigDecimal limitPrice, BigDecimal fillPrice, long notionalPaise,
            long estimatedFeesPaise, long realizedPnlDeltaPaise,
            long cashAfterPaise, Instant exchangeAt, Instant observedAt, Instant createdAt) {}
    public record Position(String instrumentKey, String optionType,
            LocalDate expiry, int lotSize, int quantity,
            long costBasisPaise, Long indicativeUnrealizedPnlPaise, boolean priceFresh) {}
    private record Wallet(long initial, long cash, boolean halted) {}
    private record Holding(int quantity, long basis, String kind, LocalDate expiry,
                           BigDecimal strike, int lotSize) {}

    @Transactional
    public Fill submit(UUID owner, UUID room, String idem, String instrumentKey,
                       LocalDate expiry, String side, int lots, BigDecimal limit) {
        if (owner==null || room==null || idem==null
                || !idem.matches("[A-Za-z0-9_-]{8,64}")
                || instrumentKey==null || !instrumentKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || expiry==null || !("BUY".equals(side)||"SELL".equals(side))
                || lots<1 || lots>20 || limit==null || limit.signum()<=0
                || limit.scale()>4 || limit.compareTo(new BigDecimal("1000000"))>0) {
            throw reject(HttpStatus.BAD_REQUEST, "Invalid F&O PAPER order");
        }
        String fingerprint = sha256(instrumentKey+"|"+expiry+"|"+side+"|"+lots+"|"
                +limit.stripTrailingZeros().toPlainString());
        // Exclusive existing PAPER-wallet lock serializes F&O and cash-equity writes.
        Wallet wallet=jdbc.sql("""
            SELECT a.initial_cash_paise,a.available_cash_paise,a.halted
            FROM customer_paper_account a JOIN customer_trading_room r
              ON r.id=a.room_id AND r.user_id=a.user_id
            WHERE a.room_id=:room AND a.user_id=:owner
              AND r.mode='PAPER' AND r.status='PAPER_FUNDED'
            FOR UPDATE OF a
            """).param("room",room).param("owner",owner)
            .query((rs,n)->new Wallet(rs.getLong("initial_cash_paise"),
                    rs.getLong("available_cash_paise"),rs.getBoolean("halted")))
            .optional().orElseThrow(()->reject(HttpStatus.NOT_FOUND,"Funded PAPER Room not found"));

        Fill previous=byIdempotencyKey(owner,room,idem);
        if(previous!=null) {
            String previousHash=jdbc.sql("""
                SELECT payload_hash FROM customer_paper_fno_order
                WHERE room_id=:room AND user_id=:owner AND idempotency_key=:idem
                """).param("room",room).param("owner",owner).param("idem",idem)
                .query(String.class).single();
            if(!fingerprint.equals(previousHash))
                throw reject(HttpStatus.CONFLICT,"F&O PAPER idempotency conflict");
            return previous;
        }
        if(!rightsVerified)
            throw reject(HttpStatus.CONFLICT,"F&O PAPER quote-use rights unverified");
        if(wallet.halted()) throw reject(HttpStatus.CONFLICT,"PAPER Room paused");

        Instant now=clock.instant();
        LocalDate today=now.atZone(IST).toLocalDate();
        if(expiry.isBefore(today) || expiry.isAfter(today.plusDays(90))
                || !calendar.isInsideCashSession(now))
            throw reject(HttpStatus.CONFLICT,"F&O PAPER session closed or contract expired");
        if(!source.enabled())
            throw reject(HttpStatus.SERVICE_UNAVAILABLE,"F&O source disabled");

        // Provider controls expiry, contract identity, lot size and price increment.
        UpstoxAnalyticsSource.FnoOptionContract contract=source
                .currentFnoOptionContracts(UNDERLYING,expiry).stream()
                .filter(c->instrumentKey.equals(c.instrumentKey())
                        && UNDERLYING.equals(c.underlyingKey())
                        && expiry.equals(c.expiry())
                        && ("CE".equals(c.side())||"PE".equals(c.side())))
                .findFirst().orElseThrow(()->reject(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Listed NSE option contract unavailable"));
        if(contract.lotSize()<1 || contract.lotSize()>10000
                || contract.strikePrice()==null || contract.strikePrice().signum()<=0
                || contract.tickSize()==null || contract.tickSize().signum()<=0)
            throw reject(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid provider contract metadata");
        int quantity=Math.multiplyExact(lots,contract.lotSize());
        if(quantity>100000 || limit.remainder(contract.tickSize()).signum()!=0)
            throw reject(HttpStatus.BAD_REQUEST,"Invalid option lot or limit-price tick");

        // This quote is from exchange-timestamped V3 stream, not REST chain LTP.
        UpstoxFnoV3LiveFeedService.PaperQuote quote=live.eligiblePaperQuote(instrumentKey)
                .orElseThrow(()->reject(HttpStatus.CONFLICT,
                        "Fresh verified F&O bid/ask unavailable"));
        // Defense in depth: executable PAPER fill requires both observation
        // and provider exchange time fresh INSIDE the active Indian session.
        if (quote.exchangeAt()==null || quote.observedAt()==null
                || !calendar.isInsideCashSession(quote.exchangeAt())
                || quote.exchangeAt().isAfter(now.plusSeconds(2))
                || quote.observedAt().isAfter(now.plusSeconds(2))
                || java.time.Duration.between(quote.exchangeAt(),now).isNegative()
                || java.time.Duration.between(quote.exchangeAt(),now)
                        .compareTo(java.time.Duration.ofSeconds(15))>0
                || java.time.Duration.between(quote.observedAt(),now).isNegative()
                || java.time.Duration.between(quote.observedAt(),now)
                        .compareTo(java.time.Duration.ofSeconds(15))>0) {
            throw reject(HttpStatus.CONFLICT,"F&O PAPER provider quote is stale or outside session");
        }
        BigDecimal price="BUY".equals(side)?quote.ask():quote.bid();
        if(("BUY".equals(side) && limit.compareTo(price)<0)
                || ("SELL".equals(side) && limit.compareTo(price)>0))
            throw reject(HttpStatus.CONFLICT,"PAPER limit does not cross provider bid/ask");

        long notional=paise(price.multiply(BigDecimal.valueOf(quantity)));
        if(notional<=0) throw reject(HttpStatus.CONFLICT,"Invalid option premium");
        // Deliberately labelled estimate, not actual exchange/brokerage tax receipt.
        long fees=Math.max(1L,Math.addExact(notional,1999L)/2000L);
        Holding holding=jdbc.sql("""
            SELECT quantity,cost_basis_paise,option_type,expiry,strike_price,lot_size
            FROM customer_paper_fno_position
            WHERE room_id=:room AND user_id=:owner AND instrument_key=:key
            FOR UPDATE
            """).param("room",room).param("owner",owner).param("key",instrumentKey)
            .query((rs,n)->new Holding(rs.getInt("quantity"),
                    rs.getLong("cost_basis_paise"),rs.getString("option_type"),
                    rs.getObject("expiry",LocalDate.class),
                    rs.getBigDecimal("strike_price"),rs.getInt("lot_size")))
            .optional().orElse(null);
        if(holding!=null && (!holding.kind().equals(contract.side())
                || !holding.expiry().equals(expiry)
                || holding.strike().compareTo(contract.strikePrice())!=0
                || holding.lotSize()!=contract.lotSize()))
            throw reject(HttpStatus.CONFLICT,"F&O contract metadata changed");

        long cashDelta, realized=0, nextBasis;
        int nextQty;
        if("BUY".equals(side)){
            long cost=Math.addExact(notional,fees);
            if(cost>wallet.initial()/3L)
                throw reject(HttpStatus.CONFLICT,"F&O PAPER per-order premium cap exceeded");
            if(cost>wallet.cash())
                throw reject(HttpStatus.CONFLICT,"Insufficient virtual PAPER cash");
            cashDelta=-cost;
            nextQty=Math.addExact(holding==null?0:holding.quantity(),quantity);
            nextBasis=Math.addExact(holding==null?0L:holding.basis(),cost);
        }else{
            if(holding==null || quantity>holding.quantity())
                throw reject(HttpStatus.CONFLICT,"Cannot short or oversell PAPER option");
            long net=Math.subtractExact(notional,fees);
            if(net<=0) throw reject(HttpStatus.CONFLICT,"Estimated fees exceed PAPER proceeds");
            cashDelta=net;
            nextQty=holding.quantity()-quantity;
            long allocated=nextQty==0?holding.basis():
                    Math.multiplyExact(holding.basis(),quantity)/holding.quantity();
            nextBasis=Math.subtractExact(holding.basis(),allocated);
            realized=Math.subtractExact(net,allocated);
        }
        long cashAfter=Math.addExact(wallet.cash(),cashDelta);
        if(cashAfter<0) throw reject(HttpStatus.CONFLICT,"Insufficient virtual cash");
        int positionRows=jdbc.sql("""
            INSERT INTO customer_paper_fno_position
                (room_id,user_id,instrument_key,expiry,strike_price,option_type,
                 lot_size,quantity,cost_basis_paise)
            VALUES (:room,:owner,:key,:expiry,:strike,:kind,:lot,:qty,:basis)
            ON CONFLICT (room_id,instrument_key) DO UPDATE
               SET quantity=EXCLUDED.quantity,cost_basis_paise=EXCLUDED.cost_basis_paise,
                   updated_at=NOW()
            WHERE customer_paper_fno_position.user_id=EXCLUDED.user_id
              AND customer_paper_fno_position.expiry=EXCLUDED.expiry
              AND customer_paper_fno_position.option_type=EXCLUDED.option_type
              AND customer_paper_fno_position.strike_price=EXCLUDED.strike_price
              AND customer_paper_fno_position.lot_size=EXCLUDED.lot_size
            """).param("room",room).param("owner",owner).param("key",instrumentKey)
            .param("expiry",expiry).param("strike",contract.strikePrice())
            .param("kind",contract.side()).param("lot",contract.lotSize())
            .param("qty",nextQty).param("basis",nextBasis).update();
        if(positionRows!=1) throw new IllegalStateException("PAPER option position conflict");
        int walletRows=jdbc.sql("""
            UPDATE customer_paper_account
            SET available_cash_paise=:cash,
                realized_pnl_paise=realized_pnl_paise+:pnl,
                ledger_version=ledger_version+1,updated_at=NOW()
            WHERE room_id=:room AND user_id=:owner AND halted=FALSE
            """).param("cash",cashAfter).param("pnl",realized)
            .param("room",room).param("owner",owner).update();
        if(walletRows!=1) throw new IllegalStateException("PAPER wallet changed");

        UUID id=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_paper_fno_order
             (id,room_id,user_id,instrument_key,expiry,strike_price,option_type,
              side,lot_size,quantity,limit_price,fill_price,notional_paise,
              estimated_fees_paise,realized_pnl_delta_paise,cash_after_paise,
              exchange_at,observed_at,idempotency_key,payload_hash)
            VALUES (:id,:room,:owner,:key,:expiry,:strike,:kind,
                    :side,:lot,:qty,:limit,:price,:notional,:fees,:pnl,:cash,
                    :exchangeAt,:observedAt,:idem,:hash)
            """).param("id",id).param("room",room).param("owner",owner)
            .param("key",instrumentKey).param("expiry",expiry)
            .param("strike",contract.strikePrice()).param("kind",contract.side())
            .param("side",side).param("lot",contract.lotSize())
            .param("qty",quantity).param("limit",limit).param("price",price)
            .param("notional",notional).param("fees",fees).param("pnl",realized)
            .param("cash",cashAfter).param("exchangeAt",java.sql.Timestamp.from(quote.exchangeAt()))
            .param("observedAt",java.sql.Timestamp.from(quote.observedAt())).param("idem",idem)
            .param("hash",fingerprint).update();
        return new Fill(id,room,instrumentKey,contract.side(),expiry,side,quantity,
                contract.lotSize(),limit,price,notional,fees,realized,cashAfter,
                quote.exchangeAt(),quote.observedAt(),now);
    }

    @Transactional(readOnly=true)
    public List<Fill> orders(UUID owner,UUID room) {
        requireOwnedPaperRoom(owner,room);
        return jdbc.sql("""
            SELECT id,room_id,instrument_key,option_type,expiry,side,quantity,
                lot_size,limit_price,fill_price,notional_paise,estimated_fees_paise,
                realized_pnl_delta_paise,cash_after_paise,exchange_at,observed_at,created_at
            FROM customer_paper_fno_order WHERE room_id=:room AND user_id=:owner
            ORDER BY created_at DESC,id DESC LIMIT 100
            """).param("room",room).param("owner",owner)
            .query((rs,n)->mapFill(rs)).list();
    }

    @Transactional(readOnly=true)
    public List<Position> positions(UUID owner,UUID room) {
        requireOwnedPaperRoom(owner,room);
        return jdbc.sql("""
            SELECT instrument_key,option_type,expiry,lot_size,quantity,cost_basis_paise
            FROM customer_paper_fno_position
            WHERE room_id=:room AND user_id=:owner AND quantity>0
            ORDER BY expiry,instrument_key LIMIT 100
            """).param("room",room).param("owner",owner)
            .query((rs,n)->{
                String key=rs.getString("instrument_key");
                int qty=rs.getInt("quantity");
                long cost=rs.getLong("cost_basis_paise");
                var quote=live.eligiblePaperQuote(key);
                Long pnl=quote.map(q->{
                    long proceeds=paise(q.bid().multiply(BigDecimal.valueOf(qty)));
                    long fee=Math.max(1,Math.addExact(proceeds,1999)/2000);
                    return Math.subtractExact(Math.subtractExact(proceeds,fee),cost);
                }).orElse(null);
                return new Position(key,rs.getString("option_type"),
                        rs.getObject("expiry",LocalDate.class),rs.getInt("lot_size"),
                        qty,cost,pnl,quote.isPresent());
            }).list();
    }

    private void requireOwnedPaperRoom(UUID owner,UUID room) {
        if(owner==null||room==null)
            throw reject(HttpStatus.UNAUTHORIZED,"Customer required");
        boolean exists=jdbc.sql("""
            SELECT TRUE FROM customer_trading_room WHERE id=:room AND user_id=:owner
            AND mode='PAPER' AND status='PAPER_FUNDED'
            """).param("room",room).param("owner",owner)
            .query(Boolean.class).optional().orElse(false);
        if(!exists)throw reject(HttpStatus.NOT_FOUND,"Funded PAPER Room not found");
    }

    private Fill byIdempotencyKey(UUID owner,UUID room,String idem) {
        return jdbc.sql("""
            SELECT id,room_id,instrument_key,option_type,expiry,side,quantity,
              lot_size,limit_price,fill_price,notional_paise,estimated_fees_paise,
              realized_pnl_delta_paise,cash_after_paise,exchange_at,observed_at,created_at
            FROM customer_paper_fno_order
            WHERE room_id=:room AND user_id=:owner AND idempotency_key=:idem
            """).param("room",room).param("owner",owner).param("idem",idem)
            .query((rs,n)->mapFill(rs)).optional().orElse(null);
    }

    private static Fill mapFill(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Fill(rs.getObject("id",UUID.class),rs.getObject("room_id",UUID.class),
                rs.getString("instrument_key"),rs.getString("option_type"),
                rs.getObject("expiry",LocalDate.class),rs.getString("side"),
                rs.getInt("quantity"),rs.getInt("lot_size"),
                rs.getBigDecimal("limit_price"),rs.getBigDecimal("fill_price"),
                rs.getLong("notional_paise"),rs.getLong("estimated_fees_paise"),
                rs.getLong("realized_pnl_delta_paise"),rs.getLong("cash_after_paise"),
                rs.getTimestamp("exchange_at").toInstant(),
                rs.getTimestamp("observed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private static long paise(BigDecimal amount) {
        return amount.multiply(ONE_HUNDRED)
                .setScale(0,RoundingMode.HALF_UP).longValueExact();
    }
    private static String sha256(String content) {
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException error){
            throw new IllegalStateException("SHA256 unavailable");
        }
    }
    private static ResponseStatusException reject(HttpStatus code,String detail) {
        return new ResponseStatusException(code,detail);
    }
}
