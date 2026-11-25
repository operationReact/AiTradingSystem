package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
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
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Self-contained SYNTHETIC options practice, not an NSE/Upstox F&O service.
 * No source, tick, provider, expiry, broker, market-session, or order API is used.
 * Wallet, positions, idempotency and fills are isolated from the normal PAPER
 * balance so a practice order can never affect a real-market PAPER position.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/paper/fno/sandbox")
public class CustomerFnoSyntheticSandboxController {
    private static final int LOT_SIZE = 10;
    private static final List<Contract> CONTRACTS = List.of(
        new Contract("SIM_FNO_CE", "Synthetic CALL (practice)", "CE", LOT_SIZE,
            new BigDecimal("100.00"), new BigDecimal("99.00")),
        new Contract("SIM_FNO_PE", "Synthetic PUT (practice)", "PE", LOT_SIZE,
            new BigDecimal("80.00"), new BigDecimal("79.00"))
    );
    private final JdbcClient jdbc;
    private final CustomerIdentityStore identities;

    public CustomerFnoSyntheticSandboxController(JdbcClient jdbc, CustomerIdentityStore identities) {
        this.jdbc = jdbc;
        this.identities = identities;
    }

    public record Contract(String code, String label, String optionType, int lotSize,
                           BigDecimal buyPrice, BigDecimal sellPrice) {}
    public record OrderRequest(String contractCode, String side, Integer lots) {}
    public record Fill(UUID id, String contractCode, String side, int lots, int quantity,
                       BigDecimal fillPrice, long notionalPaise, long feesPaise,
                       long cashDeltaPaise, long cashAfterPaise,
                       long realizedPnlDeltaPaise, Instant createdAt) {}
    public record Position(String contractCode, int quantity, long costBasisPaise) {}
    public record State(String mode, boolean synthetic, boolean exchangeDataUsed,
                        boolean brokerOrdersEnabled, long initialCashPaise,
                        long availableCashPaise, long realizedPnlPaise,
                        boolean ledgerReconciled, long recordedFills,
                        List<Contract> contracts, List<Position> positions, List<Fill> orders) {}

    private record Funded(long initial, boolean halted) {}
    private record Account(long initial, long cash, long realized) {}
    private record Holding(int quantity, long basis) {}
    private record Totals(long cashDelta, long realizedDelta, long count) {}

    @GetMapping("/state")
    @Transactional(readOnly = true)
    public ResponseEntity<State> state(@PathVariable UUID roomId, Authentication authentication) {
        UUID owner = owner(authentication);
        Funded funded = requireFunded(owner, roomId, false);
        return noStore(snapshot(owner, roomId, funded));
    }

    @PostMapping("/orders")
    @Transactional
    public ResponseEntity<Fill> submit(@PathVariable UUID roomId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody OrderRequest request, Authentication authentication) {
        UUID owner = owner(authentication);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{8,64}")
                || request == null || request.contractCode() == null
                || !("BUY".equals(request.side()) || "SELL".equals(request.side()))
                || request.lots() == null || request.lots() < 1 || request.lots() > 20) {
            throw reject(HttpStatus.BAD_REQUEST, "Invalid synthetic practice order");
        }
        Contract contract = contract(request.contractCode());
        // Serializes all sandbox submissions for this room on the funded room's
        // existing wallet row. We NEVER modify that normal PAPER wallet.
        Funded funded = requireFunded(owner, roomId, true);
        // Practice balance is completely separate from the exchange-backed
        // PAPER wallet; its default halted flag does not control training.

        jdbc.sql("""
            INSERT INTO customer_fno_sandbox_account
                (room_id,user_id,initial_cash_paise,available_cash_paise)
            VALUES (:room,:owner,:initial,:initial) ON CONFLICT (room_id) DO NOTHING
            """).param("room", roomId).param("owner", owner)
            .param("initial", funded.initial()).update();

        Account account = jdbc.sql("""
            SELECT initial_cash_paise,available_cash_paise,realized_pnl_paise
            FROM customer_fno_sandbox_account
            WHERE room_id=:room AND user_id=:owner FOR UPDATE
            """).param("room",roomId).param("owner",owner)
            .query((rs,n)->new Account(rs.getLong(1),rs.getLong(2),rs.getLong(3)))
            .optional().orElseThrow(()->reject(HttpStatus.CONFLICT,"Sandbox account unavailable"));

        String fingerprint = sha256(request.contractCode()+"|"+request.side()+"|"+request.lots());
        Fill previous = jdbc.sql("""
            SELECT id,contract_code,side,lots,quantity,fill_price,notional_paise,
                fees_paise,cash_delta_paise,cash_after_paise,realized_pnl_delta_paise,created_at,
                payload_hash
            FROM customer_fno_sandbox_order
            WHERE room_id=:room AND user_id=:owner AND idempotency_key=:key
            """).param("room",roomId).param("owner",owner).param("key",idempotencyKey)
            .query((rs,n)->{
                if (!fingerprint.equals(rs.getString("payload_hash")))
                    throw reject(HttpStatus.CONFLICT,"Synthetic order idempotency conflict");
                return mapFill(rs,n);
            }).optional().orElse(null);
        if (previous != null) return noStore(previous);

        Totals audit = totals(owner,roomId);
        if (account.cash() != Math.addExact(account.initial(),audit.cashDelta())
                || account.realized() != audit.realizedDelta())
            throw reject(HttpStatus.CONFLICT,"Synthetic sandbox ledger mismatch");

        int quantity = Math.multiplyExact(request.lots(), contract.lotSize());
        BigDecimal price = "BUY".equals(request.side())
                ? contract.buyPrice() : contract.sellPrice();
        long notional = Math.multiplyExact(price.movePointRight(2).longValueExact(), quantity);
        long fees = Math.max(100, (notional + 999) / 1000); // SYNTHETIC estimate only
        Holding holding = jdbc.sql("""
            SELECT quantity,cost_basis_paise FROM customer_fno_sandbox_position
            WHERE room_id=:room AND user_id=:owner AND contract_code=:code
            FOR UPDATE
            """).param("room",roomId).param("owner",owner).param("code",contract.code())
            .query((rs,n)->new Holding(rs.getInt(1),rs.getLong(2)))
            .optional().orElse(new Holding(0,0));

        long delta,realized=0, nextBasis;
        int nextQty;
        if ("BUY".equals(request.side())) {
            long cost = Math.addExact(notional, fees);
            if (cost > account.initial()/3L)
                throw reject(HttpStatus.CONFLICT,"Synthetic practice per-order budget exceeded");
            if (cost > account.cash())
                throw reject(HttpStatus.CONFLICT,"Insufficient synthetic practice cash");
            nextQty = Math.addExact(holding.quantity(), quantity);
            if (nextQty > 10000)
                throw reject(HttpStatus.CONFLICT,"Synthetic practice position cap exceeded");
            nextBasis = Math.addExact(holding.basis(),cost);
            delta = -cost;
        } else {
            if (quantity > holding.quantity())
                throw reject(HttpStatus.CONFLICT,"Cannot short or oversell synthetic options");
            nextQty = holding.quantity() - quantity;
            long allocated = quantity == holding.quantity()
                    ? holding.basis()
                    : Math.multiplyExact(holding.basis(),quantity)/holding.quantity();
            nextBasis = Math.subtractExact(holding.basis(),allocated);
            delta = Math.subtractExact(notional,fees);
            realized = Math.subtractExact(delta,allocated);
            if (delta <= 0)
                throw reject(HttpStatus.CONFLICT,"Synthetic proceeds less than fees");
        }
        long cashAfter = Math.addExact(account.cash(),delta);
        if (cashAfter < 0) throw reject(HttpStatus.CONFLICT,"Insufficient synthetic cash");

        int changed = jdbc.sql("""
            INSERT INTO customer_fno_sandbox_position
                (room_id,user_id,contract_code,quantity,cost_basis_paise)
            VALUES (:room,:owner,:code,:qty,:basis)
            ON CONFLICT (room_id,contract_code)
            DO UPDATE SET quantity=EXCLUDED.quantity,
                cost_basis_paise=EXCLUDED.cost_basis_paise,updated_at=NOW()
            WHERE customer_fno_sandbox_position.user_id=EXCLUDED.user_id
            """).param("room",roomId).param("owner",owner)
            .param("code",contract.code()).param("qty",nextQty)
            .param("basis",nextBasis).update();
        if (changed != 1) throw reject(HttpStatus.CONFLICT,"Synthetic position conflict");
        changed = jdbc.sql("""
            UPDATE customer_fno_sandbox_account SET available_cash_paise=:cash,
                realized_pnl_paise=realized_pnl_paise+:realized,
                ledger_version=ledger_version+1,updated_at=NOW()
            WHERE room_id=:room AND user_id=:owner
            """).param("room",roomId).param("owner",owner)
            .param("cash",cashAfter).param("realized",realized).update();
        if (changed != 1) throw reject(HttpStatus.CONFLICT,"Synthetic account conflict");
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_fno_sandbox_order
               (id,room_id,user_id,contract_code,side,lots,quantity,
                fill_price,notional_paise,fees_paise,cash_delta_paise,
                cash_after_paise,realized_pnl_delta_paise,idempotency_key,payload_hash)
            VALUES (:id,:room,:owner,:code,:side,:lots,:qty,:price,:notional,
                :fees,:delta,:cash,:realized,:key,:hash)
            """).param("id",orderId).param("room",roomId).param("owner",owner)
            .param("code",contract.code()).param("side",request.side())
            .param("lots",request.lots()).param("qty",quantity)
            .param("price",price).param("notional",notional)
            .param("fees",fees).param("delta",delta).param("cash",cashAfter)
            .param("realized",realized).param("key",idempotencyKey)
            .param("hash",fingerprint).update();
        return noStore(new Fill(orderId,contract.code(),request.side(),request.lots(),
                quantity,price,notional,fees,delta,cashAfter,realized,Instant.now()));
    }

    private State snapshot(UUID owner, UUID roomId, Funded funded) {
        Account account = jdbc.sql("""
            SELECT initial_cash_paise,available_cash_paise,realized_pnl_paise
            FROM customer_fno_sandbox_account WHERE room_id=:room AND user_id=:owner
            """).param("room",roomId).param("owner",owner)
            .query((rs,n)->new Account(rs.getLong(1),rs.getLong(2),rs.getLong(3)))
            .optional().orElse(new Account(funded.initial(),funded.initial(),0));
        Totals audit = totals(owner,roomId);
        boolean reconciled = account.cash()==Math.addExact(account.initial(),audit.cashDelta())
            && account.realized()==audit.realizedDelta();
        List<Position> positions = jdbc.sql("""
            SELECT contract_code,quantity,cost_basis_paise
            FROM customer_fno_sandbox_position
            WHERE room_id=:room AND user_id=:owner AND quantity>0
            ORDER BY contract_code
            """).param("room",roomId).param("owner",owner)
            .query((rs,n)->new Position(rs.getString(1),rs.getInt(2),rs.getLong(3)))
            .list();
        List<Fill> orders = jdbc.sql("""
            SELECT id,contract_code,side,lots,quantity,fill_price,notional_paise,
                fees_paise,cash_delta_paise,cash_after_paise,realized_pnl_delta_paise,created_at
            FROM customer_fno_sandbox_order
            WHERE room_id=:room AND user_id=:owner
            ORDER BY created_at DESC,id DESC LIMIT 100
            """).param("room",roomId).param("owner",owner).query(CustomerFnoSyntheticSandboxController::mapFill)
            .list();
        return new State("SYNTHETIC_PRACTICE",true,false,false,account.initial(),
            account.cash(),account.realized(),reconciled,audit.count(),
            CONTRACTS,positions,orders);
    }

    private Totals totals(UUID owner,UUID room) {
        return jdbc.sql("""
            SELECT COALESCE(SUM(cash_delta_paise),0),
                COALESCE(SUM(realized_pnl_delta_paise),0),COUNT(*)
            FROM customer_fno_sandbox_order WHERE user_id=:owner AND room_id=:room
            """).param("owner",owner).param("room",room)
            .query((rs,n)->new Totals(rs.getLong(1),rs.getLong(2),rs.getLong(3))).single();
    }

    private Funded requireFunded(UUID owner,UUID room,boolean lock) {
        String sql = """
            SELECT a.initial_cash_paise,a.halted FROM customer_paper_account a
            JOIN customer_trading_room r ON r.id=a.room_id AND r.user_id=a.user_id
            WHERE a.user_id=:owner AND a.room_id=:room
              AND r.mode='PAPER' AND r.status='PAPER_FUNDED'
              AND r.market_scope IN ('FNO','ALL')
              AND r.focus IN ('SWING','INTRADAY')
            """ + (lock ? " FOR UPDATE OF a" : "");
        return jdbc.sql(sql).param("owner",owner).param("room",room)
            .query((rs,n)->new Funded(rs.getLong(1),rs.getBoolean(2)))
            .optional().orElseThrow(()->reject(HttpStatus.NOT_FOUND,
                "Funded PAPER F&O room not found"));
    }

    private UUID owner(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(a->
                    "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw reject(HttpStatus.UNAUTHORIZED,"Customer session required");
        }
        return identities.find("cognito",token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a->"ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(()->reject(HttpStatus.UNAUTHORIZED,"Customer not linked"));
    }
    private static Fill mapFill(ResultSet rs,int n) throws SQLException {
        return new Fill(rs.getObject("id",UUID.class),rs.getString("contract_code"),
            rs.getString("side"),rs.getInt("lots"),rs.getInt("quantity"),
            rs.getBigDecimal("fill_price"),rs.getLong("notional_paise"),
            rs.getLong("fees_paise"),rs.getLong("cash_delta_paise"),
            rs.getLong("cash_after_paise"),rs.getLong("realized_pnl_delta_paise"),
            rs.getTimestamp("created_at").toInstant());
    }
    private static Contract contract(String code) {
        return CONTRACTS.stream().filter(c->c.code().equals(code)).findFirst()
            .orElseThrow(()->reject(HttpStatus.BAD_REQUEST,"Synthetic contract unavailable"));
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available",ex);
        }
    }
    private static ResponseStatusException reject(HttpStatus code,String message) {
        return new ResponseStatusException(code,message);
    }
    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
