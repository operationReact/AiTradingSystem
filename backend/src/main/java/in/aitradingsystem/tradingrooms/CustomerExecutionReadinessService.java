package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.execution.TradingExecutionAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared PAPER/LIVE order preflight boundary. Both modes use the same
 * validated instrument/order vocabulary, but NEVER share funds, orders
 * or execution authority. No provider order API is invoked from here.
 */
@Service
public class CustomerExecutionReadinessService {
    private final JdbcClient jdbc;
    private final TradingExecutionAuthority authority;
    private final CustomerAngelOneSessionService broker;
    private final CustomerUpstoxOAuthService upstox;
    private final InstrumentMasterService instruments;

    public CustomerExecutionReadinessService(
            JdbcClient jdbc, CustomerAngelOneSessionService broker,
            CustomerUpstoxOAuthService upstox, InstrumentMasterService instruments) {
        this(jdbc, broker, upstox, instruments, new TradingExecutionAuthority(jdbc));
    }

    @Autowired
    public CustomerExecutionReadinessService(
            JdbcClient jdbc, CustomerAngelOneSessionService broker,
            CustomerUpstoxOAuthService upstox, InstrumentMasterService instruments,
            TradingExecutionAuthority authority) {
        this.authority = authority;
        this.jdbc = jdbc;
        this.broker = broker;
        this.upstox = upstox;
        this.instruments = instruments;
    }

    public record Readiness(UUID roomId, String mode, String roomStatus,
                            String selectedBroker, boolean brokerConnected, boolean virtualCashReady,
                            boolean accountWideRiskReady, boolean brokerExecutionApproved,
                            boolean marketDataVerified, boolean executionEnabled,
                            List<String> blockers, GlobalAutomation globalAutomation) {

        /** Read-only global PAPER automation state. Customers can see it; nothing here changes it. */
        public record GlobalAutomation(String tradingMode, String orderExecutionMode, boolean automatedPaper) {}
    }

    public record Preflight(UUID roomId, String mode, long instrumentId,
                            String tradingSymbol, String exchange, String side,
                            int quantity, String orderType, String duration,
                            BigDecimal limitPrice, boolean executable,
                            List<String> blockers) {}

    private record Room(String mode, String status, String marketScope) {}

    public Readiness readiness(UUID owner, UUID roomId) {
        Room room = ownedRoom(owner, roomId);
        // A resumed PAPER wallet remains funded. The halted bit controls
        // manual execution, not the existence of the virtual allocation.
        Boolean paperHalted = null;
        if ("PAPER".equals(room.mode())) {
            paperHalted = jdbc.sql("""
                    SELECT a.halted FROM customer_paper_account a
                    WHERE a.room_id = :id AND a.user_id = :user
                      AND a.available_cash_paise >= 0
                    """)
                .param("id", roomId).param("user", owner)
                .query(Boolean.class).optional().orElse(null);
        }
        boolean paperFunded = paperHalted != null;
        // A connection to some OTHER broker must not satisfy this room.
        // A LIVE Room has exactly one persisted owner-chosen broker.
        String selected = "LIVE".equals(room.mode())
            ? selectedLiveBroker(owner, roomId) : null;
        boolean connected = "LIVE".equals(room.mode())
            && selected != null && brokerConnected(owner, selected);
        // Profile exchanges/products/order_types are verified with the owner's
        // active OAuth session. The platform Analytics token is not a trading
        // authorization, and profile permission is not permission to dispatch.
        CustomerUpstoxOAuthService.FnoEntitlement fnoProfile =
            connected && "UPSTOX".equals(selected) && !"EQUITY".equals(room.marketScope())
                ? upstox.fnoEntitlement(owner) : null;
        List<String> blockers = new ArrayList<>();
        if ("PAPER".equals(room.mode())) {
            if (!paperFunded || !"PAPER_FUNDED".equals(room.status())) {
                blockers.add("PAPER_WALLET_NOT_FUNDED");
            } else if (paperHalted) {
                blockers.add("PAPER_ROOM_PAUSED");
            }
            // The generic cash-equity execution preflight is separate from
            // the purpose-built rights/quote-gated F&O PAPER order engine.
            blockers.add("CUSTOMER_PAPER_ORDER_ENGINE_NOT_ENABLED");
        } else {
            if (selected == null) {
                blockers.add("ROOM_BROKER_ASSIGNMENT_NOT_CONFIGURED");
            }
            if (!connected) blockers.add("BROKER_NOT_CONNECTED");
            // Selecting a broker does not establish approved static IP,
            // reconciled funds, risk policy, or execution consent.
            blockers.add("BROKER_APPROVED_HOSTING_NOT_VERIFIED");
            blockers.add("REGISTERED_STATIC_IP_NOT_VERIFIED");
            blockers.add("ACCOUNT_WIDE_RISK_AND_RECONCILIATION_NOT_ENABLED");
            blockers.add("EXPLICIT_LIVE_EXECUTION_CONSENT_NOT_CONFIGURED");
            if (!"EQUITY".equals(room.marketScope())) {
                // LIVE room creation is an intent, never a permission grant.
                // F&O requires a segment-authorized account and separate
                // F&O order adapter/margin controls before any broker action.
                if (fnoProfile == null || !fnoProfile.supportsLongOptionLimits()) {
                    blockers.add("FNO_BROKER_SEGMENT_PERMISSION_NOT_VERIFIED");
                    if (fnoProfile != null) blockers.add(fnoProfile.status());
                }
                blockers.add("FNO_CONTRACT_MARGIN_AND_RISK_NOT_VERIFIED");
                blockers.add("FNO_LIVE_ORDER_ADAPTER_NOT_ENABLED");
            }
        }
        // The customer trading execution engine is unavailable in this stage.
        // All mode-specific order submissions fail closed, regardless of
        // application flags, UI state, or client-supplied claims.
        blockers.add("EXECUTION_DISABLED");
        return new Readiness(roomId, room.mode(), room.status(), selected, connected, paperFunded,
                false, false, false, false, List.copyOf(blockers), globalAutomation());
    }

    private Readiness.GlobalAutomation globalAutomation() {
        TradingExecutionAuthority.Snapshot snapshot = authority.current();
        return new Readiness.GlobalAutomation(snapshot.tradingMode(),
                snapshot.orderExecutionMode() == null ? null : snapshot.orderExecutionMode().name(),
                snapshot.automatedPaper());
    }

    /**
     * Explicitly binds an owner-selected broker to a LIVE Room. No PIN, token,
     * trading capital, order authorization, or broker request crosses this API.
     * The composite FK prevents persistence against PAPER Rooms.
     */
    @Transactional
    public Readiness selectLiveBroker(UUID owner, UUID roomId, String selected) {
        if (!"ANGEL_ONE".equals(selected) && !"UPSTOX".equals(selected)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Choose Angel One or Upstox");
        }
        Room room = jdbc.sql("""
                SELECT mode, status, market_scope FROM customer_trading_room
                WHERE id=:room AND user_id=:owner FOR UPDATE
                """)
            .param("room", roomId).param("owner", owner)
            .query((rs, row) -> new Room(rs.getString("mode"), rs.getString("status"), rs.getString("market_scope")))
            .optional().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Trading Room not found"));
        if (!"LIVE".equals(room.mode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Paper Rooms cannot select a LIVE broker");
        }
        int updated = jdbc.sql("""
                INSERT INTO customer_live_room_broker
                    (room_id,user_id,mode,broker)
                VALUES (:room,:owner,'LIVE',:broker)
                ON CONFLICT (room_id) DO UPDATE
                SET broker=EXCLUDED.broker, updated_at=NOW()
                WHERE customer_live_room_broker.user_id=EXCLUDED.user_id
                """)
            .param("room", roomId).param("owner", owner)
            .param("broker", selected).update();
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Live broker assignment conflicts with room ownership");
        }
        // Re-evaluate the authoritative readiness, which remains nonexecutable.
        return readiness(owner, roomId);
    }

    private String selectedLiveBroker(UUID owner, UUID roomId) {
        return jdbc.sql("""
                SELECT broker FROM customer_live_room_broker
                WHERE room_id=:room AND user_id=:owner AND mode='LIVE'
                """)
            .param("room", roomId).param("owner", owner)
            .query(String.class).optional().orElse(null);
    }

    private boolean brokerConnected(UUID owner, String selected) {
        return switch (selected) {
            case "ANGEL_ONE" -> broker.status(owner).connected();
            case "UPSTOX" -> upstox.status(owner).connected();
            default -> false;
        };
    }

    public Preflight preflight(UUID owner, UUID roomId, long instrumentId,
                              String side, int quantity, BigDecimal limitPrice) {
        Readiness readiness = readiness(owner, roomId);
        if ("FNO".equals(ownedRoom(owner, roomId).marketScope())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cash equity order preflight is unavailable in an F&O-only room");
        }
        Instrument instrument = instruments.requireActiveInstrument(instrumentId);
        if (!("NSE".equals(instrument.exchange()) || "BSE".equals(instrument.exchange()))
                || !("EQ".equalsIgnoreCase(instrument.instrumentType())
                    || "EQUITY".equalsIgnoreCase(instrument.instrumentType())
                    || "STOCK".equalsIgnoreCase(instrument.instrumentType()))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Only active NSE/BSE cash equities are supported");
        }
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only BUY or SELL order intents are supported");
        }
        if (quantity < 1 || quantity > 10000 || limitPrice == null
                || limitPrice.signum() <= 0 || limitPrice.scale() > 2
                || limitPrice.compareTo(new BigDecimal("10000000")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid quantity or proposed limit price");
        }
        List<String> blockers = new ArrayList<>(readiness.blockers());
        blockers.add("AUTHORITATIVE_QUOTE_NOT_VERIFIED");
        blockers.add("SERVER_SIDE_ORDER_RISK_NOT_EVALUATED");
        return new Preflight(roomId, readiness.mode(), instrumentId,
                instrument.tradingSymbol(), instrument.exchange(), side,
                quantity, "LIMIT", "DAY", limitPrice, false, List.copyOf(blockers));
    }

    private Room ownedRoom(UUID owner, UUID roomId) {
        return jdbc.sql("""
                SELECT mode, status, market_scope
                FROM customer_trading_room
                WHERE id = :id AND user_id = :user
                """)
            .param("id", roomId).param("user", owner)
            .query((rs, row) -> new Room(rs.getString("mode"), rs.getString("status"), rs.getString("market_scope")))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Trading Room not found"));
    }
}
