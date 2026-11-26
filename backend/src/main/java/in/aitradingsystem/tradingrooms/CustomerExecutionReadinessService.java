package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.execution.TradingExecutionAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import in.aitradingsystem.provider.upstox.UpstoxRegisteredIpAssessment;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final IndianCashMarketCalendar cashCalendar;

    public CustomerExecutionReadinessService(
            JdbcClient jdbc, CustomerAngelOneSessionService broker,
            CustomerUpstoxOAuthService upstox, InstrumentMasterService instruments) {
        this(jdbc, broker, upstox, instruments, new TradingExecutionAuthority(jdbc));
    }

    public CustomerExecutionReadinessService(
            JdbcClient jdbc, CustomerAngelOneSessionService broker,
            CustomerUpstoxOAuthService upstox, InstrumentMasterService instruments,
            TradingExecutionAuthority authority) {
        this(jdbc, broker, upstox, instruments, authority, null);
    }

    @Autowired
    public CustomerExecutionReadinessService(
            JdbcClient jdbc, CustomerAngelOneSessionService broker,
            CustomerUpstoxOAuthService upstox, InstrumentMasterService instruments,
            TradingExecutionAuthority authority, IndianCashMarketCalendar cashCalendar) {
        this.authority = authority;
        this.jdbc = jdbc;
        this.broker = broker;
        this.upstox = upstox;
        this.instruments = instruments;
        this.cashCalendar = cashCalendar;
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
                            String selectedBroker,
                            String tradingSymbol, String exchange, String side,
                            int quantity, String orderType, String duration,
                            BigDecimal limitPrice, BigDecimal estimatedNotional,
                            BigDecimal availableBrokerFunds, BigDecimal roomBudget,
                            BigDecimal todayRoomBuyNotional,
                            boolean brokerCashVerified, boolean roomExposureVerified,
                            boolean executable,
                            List<String> blockers) {}

    private record Room(String mode, String status, String marketScope, long plannedBudgetInr) {}

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
        CustomerUpstoxOAuthService.EquityEntitlement equityProfile =
            connected && "UPSTOX".equals(selected)
                    && !"FNO".equals(room.marketScope())
                ? upstox.equityEntitlement(owner) : null;
        boolean registeredIpMatches = connected && "UPSTOX".equals(selected)
            && !"FNO".equals(room.marketScope())
            && registeredUpstoxIpMatches(owner);
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
            if (!"FNO".equals(room.marketScope()) && "UPSTOX".equals(selected)
                    && (equityProfile == null || !equityProfile.supportsIntradayLimits())) {
                blockers.add("UPSTOX_EQUITY_INTRADAY_PERMISSION_NOT_VERIFIED");
                if (equityProfile != null) blockers.add(equityProfile.status());
            }
            // Selecting a broker does not establish approved static IP,
            // reconciled funds, risk policy, or execution consent.
            blockers.add("BROKER_APPROVED_HOSTING_NOT_VERIFIED");
            if (!registeredIpMatches) blockers.add("REGISTERED_STATIC_IP_NOT_VERIFIED");
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
            .query((rs, row) -> new Room(rs.getString("mode"), rs.getString("status"),
                rs.getString("market_scope"), rs.getLong("planned_budget_inr")))
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

    private boolean registeredUpstoxIpMatches(UUID owner) {
        try {
            return UpstoxRegisteredIpAssessment.assess(
                upstox.staticIps(owner).data()).registrationMatchesEc2();
        } catch (RuntimeException unavailable) {
            return false;
        }
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
        if (instrument.tickSize() == null || instrument.tickSize().signum() <= 0
                || limitPrice.remainder(instrument.tickSize()).signum() != 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Proposed limit price does not match the instrument tick size");
        }
        Room room = ownedRoom(owner, roomId);
        List<String> blockers = new ArrayList<>(readiness.blockers());
        boolean cashSessionOpen = cashCalendar == null
            || cashCalendar.isInsideCashSession(Instant.now());
        if (!cashSessionOpen) blockers.add("CASH_MARKET_SESSION_CLOSED");
        if (cashSessionOpen
                && !hasFreshCustomerQuote(owner, readiness.selectedBroker(), instrument)) {
            blockers.add("AUTHORITATIVE_QUOTE_NOT_VERIFIED");
        }
        BigDecimal notional = limitPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal budget = BigDecimal.valueOf(room.plannedBudgetInr());
        BigDecimal availableFunds = readAvailableFunds(owner, readiness.selectedBroker());
        BigDecimal todayRoomBuyNotional = readTodayRoomBuyNotional(owner, roomId);
        boolean cashBalanceVerified = availableFunds != null;
        boolean roomExposureVerified = todayRoomBuyNotional != null;
        blockers.addAll(assessOrderFunds(side, notional, budget, availableFunds,
            todayRoomBuyNotional));
        if ("SELL".equals(side)) blockers.add("SELL_POSITION_NOT_VERIFIED");
        if (newOrdersHalted()) blockers.add("GLOBAL_NEW_ORDERS_HALTED");
        if (hasUnreconciledLiveOrder(owner)) blockers.add("UNRECONCILED_LIVE_EQUITY_ORDERS_EXIST");
        return new Preflight(roomId, readiness.mode(), instrumentId,
                readiness.selectedBroker(),
                instrument.tradingSymbol(), instrument.exchange(), side,
                quantity, "LIMIT", "DAY", limitPrice, notional, availableFunds, budget,
                todayRoomBuyNotional, cashBalanceVerified, roomExposureVerified,
                false, List.copyOf(blockers));
    }

    static List<String> assessOrderFunds(String side, BigDecimal notional,
            BigDecimal roomBudget, BigDecimal availableFunds,
            BigDecimal todayRoomBuyNotional) {
        List<String> blockers = new ArrayList<>();
        if (todayRoomBuyNotional == null) {
            blockers.add("ROOM_DAILY_EXPOSURE_NOT_VERIFIED");
        } else if ("BUY".equals(side)
                && todayRoomBuyNotional.add(notional).compareTo(roomBudget) > 0) {
            blockers.add("ORDER_EXCEEDS_REMAINING_ROOM_BUDGET");
        }
        if (availableFunds == null) {
            blockers.add("BROKER_AVAILABLE_FUNDS_NOT_VERIFIED");
        } else if ("BUY".equals(side) && notional.compareTo(availableFunds) > 0) {
            blockers.add("INSUFFICIENT_BROKER_AVAILABLE_FUNDS");
        }
        return List.copyOf(blockers);
    }

    /** Today's non-rejected BUY intents consume room budget for the full session. */
    private BigDecimal readTodayRoomBuyNotional(UUID owner, UUID roomId) {
        try {
            ZoneId zone = ZoneId.of("Asia/Kolkata");
            LocalDate today = LocalDate.now(zone);
            Instant start = today.atStartOfDay(zone).toInstant();
            Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();
            return jdbc.sql("""
                    SELECT COALESCE(SUM(limit_price * quantity), 0)
                    FROM customer_live_equity_order
                    WHERE user_id=:owner AND room_id=:room AND side='BUY'
                      AND state <> 'REJECTED'
                      AND created_at >= :start AND created_at < :end
                    """)
                .param("owner", owner).param("room", roomId)
                .param("start", start).param("end", end)
                .query(BigDecimal.class).single();
        } catch (RuntimeException unavailable) {
            // Ledger failure is not a clean zero-exposure account.
            return null;
        }
    }

    /** Broker-specific funds read; values are never inferred from platform or paper balances. */
    private BigDecimal readAvailableFunds(UUID owner, String selectedBroker) {
        try {
            if ("UPSTOX".equals(selectedBroker)) {
                var data = upstox.funds(owner).data();
                return decimal(data == null ? null
                    : data.path("equity").path("available_margin"));
            }
            if ("ANGEL_ONE".equals(selectedBroker)) {
                var data = broker.funds(owner).data();
                if (data == null) return null;
                var root = data.has("data") && data.get("data").isObject()
                    ? data.get("data") : data;
                var value = root.has("availablecash") ? root.get("availablecash")
                    : root.get("availableCash");
                return decimal(value);
            }
        } catch (RuntimeException unavailableOrUnverified) {
            // Missing/stale/failed broker funds are a blocker, not a zero or fallback.
        }
        return null;
    }

    private BigDecimal decimal(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        try {
            BigDecimal parsed = value.isNumber() ? value.decimalValue()
                : new BigDecimal(value.asText().trim());
            return parsed.signum() < 0 ? null : parsed;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private boolean newOrdersHalted() {
        try {
            return jdbc.sql("SELECT new_orders_halted FROM trading_control WHERE id=1")
                .query(Boolean.class).single();
        } catch (RuntimeException unavailable) {
            // If the control cannot be read, fail closed as halted.
            return true;
        }
    }

    private boolean hasUnreconciledLiveOrder(UUID owner) {
        try {
            return jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM customer_live_equity_order
                      WHERE user_id=:owner
                        AND state IN ('SUBMITTING','UNKNOWN','ACKNOWLEDGED','PARTIALLY_FILLED'))
                    """)
                .param("owner", owner).query(Boolean.class).single();
        } catch (RuntimeException unavailable) {
            // A missing ledger or DB read must not be interpreted as a clean account.
            return true;
        }
    }

    /** Read-only quote gate: always use the selected customer's own broker session. */
    private boolean hasFreshCustomerQuote(UUID owner, String selectedBroker, Instrument instrument) {
        try {
            Instant now = Instant.now();
            if ("ANGEL_ONE".equals(selectedBroker)) {
                var quote = broker.quoteDetail(owner, instrument);
                return quote.ltp() != null && quote.ltp().signum() > 0
                    && quote.exchangeAt() != null
                    && !quote.exchangeAt().isAfter(now.plusSeconds(2))
                    && !quote.exchangeAt().isBefore(now.minusSeconds(5))
                    && !quote.bids().isEmpty() && !quote.asks().isEmpty();
            }
            if ("UPSTOX".equals(selectedBroker)) {
                var quote = upstox.cashEquityQuote(owner, instrument);
                return quote.lastPrice().signum() > 0
                    && quote.bestBid().signum() > 0 && quote.bestAsk().signum() > 0
                    && quote.exchangeAt() != null
                    && !quote.exchangeAt().isAfter(now.plusSeconds(2))
                    && !quote.exchangeAt().isBefore(now.minusSeconds(5));
            }
        } catch (RuntimeException unavailableOrUnverified) {
            // A failed provider read is a blocker, never a fallback to platform data.
        }
        return false;
    }

    private Room ownedRoom(UUID owner, UUID roomId) {
        return jdbc.sql("""
                SELECT mode, status, market_scope, planned_budget_inr
                FROM customer_trading_room
                WHERE id = :id AND user_id = :user
                """)
            .param("id", roomId).param("user", owner)
            .query((rs, row) -> new Room(rs.getString("mode"), rs.getString("status"),
                rs.getString("market_scope"), rs.getLong("planned_budget_inr")))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Trading Room not found"));
    }
}
