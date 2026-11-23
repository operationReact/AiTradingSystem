package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import in.aitradingsystem.provider.upstox.UpstoxFnoLimitOrderPayload;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import in.aitradingsystem.provider.upstox.UpstoxRegisteredIpAssessment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Narrow real-money Upstox NSE NIFTY CE/PE manual order coordinator.
 *
 * Real orders are available only to the authenticated room owner, with exact
 * per-request confirmation AND a separately activated server policy. No
 * strategy can call this service unattended. PAPER wallets are never touched.
 * The claim is committed before the ONE broker POST. UNKNOWN is irreversible
 * without an authenticated provider order-history reconciliation.
 *
 * The initial live pilot permits ONE listed lot per request, long BUY or
 * SELL-to-close only, with a hard bounded rupee premium risk. This is not a
 * generalized autonomous execution engine.
 */
@Service
public final class CustomerUpstoxFnoLiveOrderService {
    private static final String UNDERLYING = "NSE_INDEX|Nifty 50";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAX_PREMIUM_INR = new BigDecimal("25000");
    private final JdbcClient jdbc;
    private final CustomerUpstoxOAuthService broker;
    private final UpstoxAnalyticsSource source;
    private final ObjectProvider<UpstoxFnoV3LiveFeedService> feeds;
    private final UpstoxFnoLimitOrderPayload payloadBuilder;
    private final MarketSessionCalendar calendar;
    private final TransactionTemplate transaction;
    private final Environment environment;
    private final Clock clock;

    public record Request(String instrumentKey, LocalDate expiry, String side,
                          int lots, BigDecimal limitPrice) {}
    public record Receipt(UUID id, UUID roomId, String instrumentKey,
                          LocalDate expiry, String side, int quantity,
                          BigDecimal limitPrice, String state, String orderTag,
                          String brokerOrderId, String outcomeCode, Instant createdAt) {}
    private record Room(String focus, long plannedBudgetInr, String broker) {}
    private record Claimed(boolean created, Receipt receipt) {}

    @Autowired
    public CustomerUpstoxFnoLiveOrderService(JdbcClient jdbc,
            CustomerUpstoxOAuthService broker, UpstoxAnalyticsSource source,
            ObjectProvider<UpstoxFnoV3LiveFeedService> feeds,
            UpstoxFnoLimitOrderPayload payloadBuilder,
            MarketSessionCalendar calendar, PlatformTransactionManager manager,
            Environment environment) {
        this(jdbc, broker, source, feeds, payloadBuilder, calendar,
            new TransactionTemplate(manager), environment, Clock.systemUTC());
    }

    CustomerUpstoxFnoLiveOrderService(JdbcClient jdbc,
            CustomerUpstoxOAuthService broker, UpstoxAnalyticsSource source,
            ObjectProvider<UpstoxFnoV3LiveFeedService> feeds,
            UpstoxFnoLimitOrderPayload payloadBuilder,
            MarketSessionCalendar calendar, TransactionTemplate transaction,
            Environment environment, Clock clock) {
        this.jdbc = jdbc;
        this.broker = broker;
        this.source = source;
        this.feeds = feeds;
        this.payloadBuilder = payloadBuilder;
        this.calendar = calendar;
        this.transaction = transaction;
        this.environment = environment;
        this.clock = clock;
    }

    /** Reads persisted receipts even if LIVE feature flags are disabled. */
    public List<Receipt> orders(UUID owner, UUID roomId) {
        requireRoom(owner, roomId);
        return jdbc.sql("""
            SELECT id,room_id,instrument_key,expiry,side,quantity,limit_price,
                   state,order_tag,broker_order_id,outcome_code,created_at
            FROM customer_upstox_fno_live_order
            WHERE user_id=:owner AND room_id=:room
            ORDER BY created_at DESC,id DESC LIMIT 100
            """)
            .param("owner", owner).param("room", roomId)
            .query((rs,n) -> receipt(rs)).list();
    }

    /** Safe server-derived capability, independent of any PAPER order rights. */
    public record Capability(boolean configured, boolean brokerConnected,
            boolean nfoPermissionVerified, boolean registeredIpMatches,
            boolean contractSourceReady, boolean sessionOpen,
            int freshExchangeTimedQuotes, boolean eligibleForOrderPreflight,
            List<String> reasons) {
        public Capability { reasons = List.copyOf(reasons); }
    }

    public Capability capability(UUID owner, UUID roomId) {
        Room room = requireRoom(owner, roomId);
        boolean flag = environment.getProperty(
            "minttick.upstox-fno.live-order-enabled", Boolean.class, false);
        boolean connected = false, nfo = false, ip = false;
        try {
            connected = "UPSTOX".equals(room.broker()) && broker.status(owner).connected();
            if (connected) {
                var entitlement = broker.fnoEntitlement(owner);
                nfo = entitlement.supportsLongOptionLimits()
                    && ("INTRADAY".equals(room.focus())
                        ? entitlement.intradayEnabled()
                        : "SWING".equals(room.focus())
                            && entitlement.carryforwardEnabled());
                ip = UpstoxRegisteredIpAssessment.assess(
                    broker.staticIps(owner).data()).registrationMatchesEc2();
            }
        } catch (RuntimeException unavailable) {
            // Customer sees only fixed booleans. No provider details or tokens.
        }
        boolean sourceReady = source.enabled();
        var feed = feeds.getIfAvailable();
        int fresh = feed == null ? 0 : Math.max(0,
            feed.status().freshExchangeTimedQuotes());
        boolean sessionOpen = calendar.isInsideFnoSession(clock.instant())
            && insideUpstoxIntradayCutoff(clock.instant(), room.focus());
        boolean eligible = flag && connected && nfo && ip
            && sourceReady && fresh > 0 && sessionOpen;
        java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
        if (!flag) reasons.add("LIVE_ORDER_POLICY_NOT_ACTIVATED");
        if (!connected) reasons.add("OWNER_UPSTOX_SESSION_NOT_CONNECTED");
        if (!nfo) reasons.add("OWNER_NFO_LIMIT_PRODUCT_NOT_VERIFIED");
        if (!ip) reasons.add("BROKER_STATIC_IP_NOT_MATCHED");
        if (!sourceReady) reasons.add("NFO_LISTING_SOURCE_UNAVAILABLE");
        if (fresh == 0) reasons.add("NO_FRESH_EXCHANGE_TIMED_OPTION_QUOTES");
        if (!sessionOpen) reasons.add("NFO_SESSION_CLOSED");
        return new Capability(flag, connected, nfo, ip, sourceReady,
            sessionOpen, fresh, eligible, reasons);
    }

    /**
     * The authenticated owner has to explicitly confirm each real-money
     * order, irrespective of persisted room selection or application flags.
     */
    public Receipt submit(UUID owner, UUID roomId, String idempotencyKey,
                          Request request, boolean confirmed) {
        validateRequest(owner, roomId, idempotencyKey, request);
        String hash = fingerprint(request);
        // An existing submitted/uncertain order is returned, NEVER sent again.
        // This works even when the session, trading hours or flags later change.
        requireRoom(owner, roomId);
        Receipt old = byIdem(owner, roomId, idempotencyKey);
        if (old != null) return matching(old, hash, owner, roomId, idempotencyKey);
        if (!confirmed) throw error(HttpStatus.PRECONDITION_REQUIRED,
            "Explicit per-order LIVE F&O confirmation required");
        if (!environment.getProperty("minttick.upstox-fno.live-order-enabled",
                Boolean.class, false)) {
            throw error(HttpStatus.CONFLICT, "Upstox LIVE F&O execution not activated");
        }

        Room room = requireRoom(owner, roomId);
        if (!"UPSTOX".equals(room.broker())) {
            throw error(HttpStatus.CONFLICT, "Select Upstox for this LIVE room");
        }
        // Global emergency stop is independent of a room's selected broker.
        // Block opening new LIVE exposure, but allow a verified SELL-to-close.
        requireLiveEntryNotHalted(request.side(), false);
        String product = switch (room.focus()) {
            case "INTRADAY" -> "I";
            case "SWING" -> "D";
            default -> throw error(HttpStatus.CONFLICT, "Unsupported F&O room horizon");
        };
        var entitlement = broker.fnoEntitlement(owner);
        if (!entitlement.supportsLongOptionLimits()
                || ("I".equals(product) && !entitlement.intradayEnabled())
                || ("D".equals(product) && !entitlement.carryforwardEnabled())) {
            throw error(HttpStatus.CONFLICT, "Upstox NFO account permission not verified");
        }
        if (!UpstoxRegisteredIpAssessment.assess(
                broker.staticIps(owner).data()).registrationMatchesEc2()) {
            throw error(HttpStatus.CONFLICT, "Broker registered static IP does not match");
        }
        Instant now = clock.instant();
        LocalDate today = now.atZone(IST).toLocalDate();
        if (!calendar.isInsideFnoSession(now)
                || !insideUpstoxIntradayCutoff(now, room.focus())
                || request.expiry().isBefore(today)
                || request.expiry().isAfter(today.plusDays(90))) {
            throw error(HttpStatus.CONFLICT, "NFO trading session or listed expiry unavailable");
        }
        if (!source.enabled()) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "F&O contract source unavailable");
        }

        // Never trust a client-declared contract, lot size, option type or tick.
        var contract = source.currentFnoOptionContracts(UNDERLYING, request.expiry()).stream()
            .filter(c -> request.instrumentKey().equals(c.instrumentKey())
                && UNDERLYING.equals(c.underlyingKey())
                && request.expiry().equals(c.expiry())
                && ("CE".equals(c.side()) || "PE".equals(c.side())))
            .findFirst().orElseThrow(() -> error(HttpStatus.UNPROCESSABLE_ENTITY,
                "Currently listed NFO option contract not verified"));
        if (contract.lotSize() <= 0 || contract.lotSize() > 10000
                || contract.tickSize() == null || contract.tickSize().signum() <= 0
                || contract.strikePrice() == null || contract.strikePrice().signum() <= 0) {
            throw error(HttpStatus.CONFLICT, "Unverified option contract metadata");
        }
        int quantity = Math.multiplyExact(contract.lotSize(), request.lots());
        var feed = feeds.getIfAvailable();
        if (feed == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "Live exchange feed not running");
        }
        var quote = feed.eligiblePaperQuote(request.instrumentKey())
            .orElseThrow(() -> error(HttpStatus.CONFLICT,
                "Current exchange-timed NFO bid and ask not verified"));
        if (!calendar.isInsideFnoSession(quote.exchangeAt())
                || quote.exchangeAt().isAfter(now.plusSeconds(2))) {
            throw error(HttpStatus.CONFLICT, "Exchange-timed NFO quote outside session");
        }
        // Narrow price collar against accidental far-from-market LIMIT orders.
        BigDecimal reference = "BUY".equals(request.side()) ? quote.ask() : quote.bid();
        if ("BUY".equals(request.side())
                ? request.limitPrice().compareTo(reference) < 0
                    || request.limitPrice().compareTo(reference.multiply(new BigDecimal("1.02"))) > 0
                : request.limitPrice().compareTo(reference) > 0
                    || request.limitPrice().compareTo(reference.multiply(new BigDecimal("0.98"))) < 0) {
            throw error(HttpStatus.CONFLICT, "NFO limit outside verified bid/ask collar");
        }
        long premium = toPaise(request.limitPrice()
            .multiply(BigDecimal.valueOf(quantity)));
        if (premium <= 0) throw error(HttpStatus.CONFLICT, "Invalid option premium");

        // Explicitly checked broker order book: do not overlay a previous
        // pending order on the same NFO contract, including external clients.
        JsonNode book = broker.orders(owner).data();
        JsonNode brokerPositions = broker.positions(owner).data();
        // Initial one-lot LIVE pilot cannot layer on ANY unsettled external
        // broker order or existing NFO exposure. An external app may already
        // have placed a different contract or hedge that our DB cannot see.
        // SELL-to-close remains available for an independently verified long.
        if ("BUY".equals(request.side()) &&
                (hasUnsettledBrokerOrders(book) || hasExistingNfoExposure(brokerPositions))) {
            throw error(HttpStatus.CONFLICT,
                "Broker orders or F&O exposure require reconciliation before LIVE BUY");
        }
        if ("SELL".equals(request.side())
                && hasOpenOrder(book, request.instrumentKey())) {
            throw error(HttpStatus.CONFLICT, "Existing broker order requires reconciliation");
        }
        int owned = 0;
        if ("SELL".equals(request.side())) {
            owned = availableLongUnits(brokerPositions,
                    request.instrumentKey(), product);
            if (owned < quantity) {
                throw error(HttpStatus.CONFLICT, "Broker has no sufficient long option units");
            }
        } else {
            JsonNode marginNode = broker.funds(owner).data()
                .path("equity").path("available_margin");
            BigDecimal margin = marginNode.isNumber() ? marginNode.decimalValue() : null;
            // Hard <= one-third room budget, <= Rs 25k pilot premium, and
            // <= 80% of actual broker-reported margin. No client cash claims.
            BigDecimal budget = BigDecimal.valueOf(room.plannedBudgetInr())
                .divide(new BigDecimal("3"), 2, RoundingMode.DOWN);
            if (margin == null || margin.signum() <= 0
                    || request.limitPrice().multiply(BigDecimal.valueOf(quantity))
                        .compareTo(MAX_PREMIUM_INR.min(budget)) > 0
                    || request.limitPrice().multiply(BigDecimal.valueOf(quantity))
                        .compareTo(margin.multiply(new BigDecimal("0.8"))) > 0) {
                throw error(HttpStatus.CONFLICT, "Insufficient verified LIVE premium budget");
            }
        }

        UUID id = UUID.randomUUID();
        String tag = "mt" + id.toString().replace("-", "").substring(0, 16);
        var params = new UpstoxFnoLimitOrderPayload.VerifiedParameters(
            UNDERLYING, contract.instrumentKey(), contract.side(), today,
            request.expiry(), contract.lotSize(), contract.tickSize(),
            request.lots(), request.side(), room.focus(), request.limitPrice(),
            owned, tag);
        ObjectNode payload = payloadBuilder.build(params);
        Claimed claim = transaction.execute(tx -> {
            // App-user row lock serializes submissions ACROSS all LIVE rooms.
            jdbc.sql("SELECT id FROM app_user WHERE id=:owner FOR UPDATE")
                .param("owner", owner).query(UUID.class).single();
            Receipt existing = byIdem(owner, roomId, idempotencyKey);
            if (existing != null) return new Claimed(false,
                matching(existing, hash, owner, roomId, idempotencyKey));
            // Recheck mutable room/broker selection inside the same lock.
            Room latest = requireRoom(owner, roomId);
            if (!room.equals(latest)) {
                throw error(HttpStatus.CONFLICT, "LIVE room changed during order preflight");
            }
            // Recheck under the transaction before reserving a real-money BUY.
            requireLiveEntryNotHalted(request.side(), true);
            long pending = jdbc.sql("""
                SELECT COUNT(*) FROM customer_upstox_fno_live_order
                WHERE user_id=:owner
                  AND state IN ('SUBMITTING','UNKNOWN','ACKNOWLEDGED')
                """).param("owner", owner).query(Long.class).single();
            if (pending > 0) {
                throw error(HttpStatus.CONFLICT, "Outstanding LIVE order needs broker reconciliation");
            }
            long daily = jdbc.sql("""
                SELECT COUNT(*) FROM customer_upstox_fno_live_order
                WHERE user_id=:owner AND created_at >= :sessionStart
                """).param("owner", owner)
                .param("sessionStart", today.atStartOfDay(IST).toInstant())
                .query(Long.class).single();
            if (daily >= 10) {
                throw error(HttpStatus.CONFLICT, "Daily LIVE pilot order cap reached");
            }
            jdbc.sql("""
                INSERT INTO customer_upstox_fno_live_order
                    (id,user_id,room_id,instrument_key,option_type,expiry,
                     side,quantity,lot_size,product,limit_price,
                     estimated_premium_paise,idempotency_key,payload_hash,
                     order_tag,state)
                VALUES (:id,:owner,:room,:instrument,:type,:expiry,
                        :side,:qty,:lot,:product,:limit,
                        :premium,:idem,:hash,:tag,'SUBMITTING')
                """)
                .param("id", id).param("owner", owner).param("room", roomId)
                .param("instrument", request.instrumentKey()).param("type", contract.side())
                .param("expiry", request.expiry()).param("side", request.side())
                .param("qty", quantity).param("lot", contract.lotSize())
                .param("product", product).param("limit", request.limitPrice())
                .param("premium", premium).param("idem", idempotencyKey)
                .param("hash", hash).param("tag", tag).update();
            return new Claimed(true, byIdem(owner, roomId, idempotencyKey));
        });
        if (claim == null) throw error(HttpStatus.SERVICE_UNAVAILABLE, "LIVE custody unavailable");
        if (!claim.created()) return claim.receipt();

        // Exactly ONE real broker POST after durable transaction commit.
        // If anything is ambiguous, keep UNKNOWN in custody. NEVER retry.
        String state = "UNKNOWN", code = "UPSTOX_FNO_ACK_UNCERTAIN";
        String brokerId = null;
        try {
            var result = broker.submitVerifiedFnoOnce(owner, payload);
            code = result.code();
            if ("ACKNOWLEDGED".equals(result.outcome())
                    && result.orderIds().size() == 1) {
                state = "ACKNOWLEDGED";
                brokerId = result.orderIds().getFirst();
            } else if ("REJECTED".equals(result.outcome())) {
                state = "REJECTED";
            }
        } catch (RuntimeException ignored) {
            // Provider accepted-but-timed-out is indistinguishable from no send.
            // Never leak exception or send a second POST.
        }
        final String finalState = state, finalCode = code, finalId = brokerId;
        Receipt result = transaction.execute(tx -> {
            int updated = jdbc.sql("""
                UPDATE customer_upstox_fno_live_order
                   SET state=:state,outcome_code=:code,
                       broker_order_id=:brokerId,updated_at=NOW()
                 WHERE id=:id AND room_id=:room AND user_id=:owner
                   AND state='SUBMITTING'
                """).param("state", finalState).param("code", finalCode)
                .param("brokerId", finalId).param("id", id)
                .param("owner", owner).param("room", roomId).update();
            if (updated != 1) {
                throw error(HttpStatus.SERVICE_UNAVAILABLE,
                    "LIVE outcome custody requires reconciliation");
            }
            return byIdem(owner, roomId, idempotencyKey);
        });
        if (result == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "LIVE outcome custody unavailable");
        }
        return result;
    }

    /**
     * Owner-initiated read-only broker history check for ACK/UNKNOWN rows.
     * No retry, cancel, modify or new broker order. A zero-length history
     * leaves the claim UNKNOWN and reserves account-wide single-flight.
     */
    public Receipt reconcile(UUID owner, UUID roomId, UUID orderId) {
        requireRoom(owner, roomId);
        Receipt original = byId(owner, roomId, orderId);
        if (original == null) throw error(HttpStatus.NOT_FOUND, "LIVE order not found");
        if (List.of("COMPLETE", "CANCELLED", "REJECTED").contains(original.state())) {
            return original;
        }
        JsonNode history = broker.orderHistoryByTag(owner, original.orderTag()).data();
        BrokerHistoryAssessment assessed = assessVerifiedBrokerHistory(history, original);
        if (assessed == null) {
            // Empty broker history is not proof of non-submission.
            return original;
        }
        String brokerId = assessed.brokerOrderId();
        String target = assessed.state();
        Receipt refreshed = transaction.execute(tx -> {
            jdbc.sql("""
                UPDATE customer_upstox_fno_live_order
                   SET state=:state,broker_order_id=:bid,
                       outcome_code=:outcomeCode,
                       updated_at=NOW()
                 WHERE id=:id AND user_id=:owner AND room_id=:room
                   AND state IN ('SUBMITTING','UNKNOWN','ACKNOWLEDGED')
                """).param("state", target).param("bid", brokerId)
                 .param("outcomeCode", assessed.outcomeCode())
                .param("id", orderId).param("owner", owner)
                .param("room", roomId).update();
            return byId(owner, roomId, orderId);
        });
        return refreshed == null ? original : refreshed;
    }

    /**
     * Evaluate broker history without trusting array order or placing trades.
     * PARTIAL, missing, contradictory or unknown fills never become COMPLETE
     * and never unblock the existing single-flight LIVE entry lock.
     */
    record BrokerHistoryAssessment(String state, String outcomeCode,
                                   String brokerOrderId, int observedFilledQuantity) {}

    static BrokerHistoryAssessment assessVerifiedBrokerHistory(
            JsonNode history, Receipt original) {
        if (history == null || !history.isArray() || history.size() > 200) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "Broker order history unverified");
        }
        if (original == null || original.quantity() < 1) {
            throw error(HttpStatus.CONFLICT, "Order custody unavailable");
        }
        if (history.isEmpty()) return null;

        String brokerId = null;
        boolean badFill = false, unknownStatus = false;
        int maxFilled = 0;
        java.util.Set<String> terminal = new java.util.HashSet<>();
        for (JsonNode event : history) {
            if (event == null || !original.orderTag().equals(event.path("tag").asText(""))
                    || !original.instrumentKey().equals(event.path("instrument_token").asText(""))
                    || !original.side().equals(event.path("transaction_type").asText(""))
                    || !event.path("quantity").canConvertToInt()
                    || event.path("quantity").asInt() != original.quantity()) {
                throw error(HttpStatus.CONFLICT, "Broker order identity mismatch");
            }
            String currentId = event.path("order_id").asText("");
            if (!currentId.matches("[A-Za-z0-9_-]{1,64}")
                    || (original.brokerOrderId() != null
                        && !original.brokerOrderId().equals(currentId))
                    || (brokerId != null && !brokerId.equals(currentId))) {
                throw error(HttpStatus.CONFLICT, "Broker order identity not verified");
            }
            brokerId = currentId;
            JsonNode fill = event.path("filled_quantity");
            if (!fill.canConvertToInt() || fill.asInt() < 0
                    || fill.asInt() > original.quantity()) {
                badFill = true;
            } else {
                maxFilled = Math.max(maxFilled, fill.asInt());
            }
            String status = event.path("status").asText("").toLowerCase(Locale.ROOT);
            switch (status) {
                case "complete" -> terminal.add("COMPLETE");
                case "rejected" -> terminal.add("REJECTED");
                case "cancelled", "canceled" -> terminal.add("CANCELLED");
                case "open", "pending", "validation pending", "open pending",
                     "put order req received", "trigger pending" -> { }
                default -> unknownStatus = true;
            }
        }
        if (terminal.size() > 1 || badFill || unknownStatus) {
            return new BrokerHistoryAssessment("UNKNOWN",
                "UPSTOX_FNO_BROKER_HISTORY_UNCERTAIN", brokerId, maxFilled);
        }
        if (terminal.contains("COMPLETE") && maxFilled == original.quantity()) {
            return new BrokerHistoryAssessment("COMPLETE",
                "UPSTOX_FNO_FULL_FILL_VERIFIED", brokerId, maxFilled);
        }
        if (maxFilled > 0 && maxFilled < original.quantity()) {
            return new BrokerHistoryAssessment("UNKNOWN",
                "UPSTOX_FNO_PARTIAL_FILL_UNCERTAIN", brokerId, maxFilled);
        }
        if (maxFilled != 0) {
            return new BrokerHistoryAssessment("UNKNOWN",
                "UPSTOX_FNO_BROKER_HISTORY_UNCERTAIN", brokerId, maxFilled);
        }
        if (terminal.contains("REJECTED")) {
            return new BrokerHistoryAssessment("REJECTED",
                "UPSTOX_FNO_ZERO_FILL_REJECTED", brokerId, 0);
        }
        if (terminal.contains("CANCELLED")) {
            return new BrokerHistoryAssessment("CANCELLED",
                "UPSTOX_FNO_ZERO_FILL_CANCELLED", brokerId, 0);
        }
        if (terminal.contains("COMPLETE")) {
            return new BrokerHistoryAssessment("UNKNOWN",
                "UPSTOX_FNO_BROKER_HISTORY_UNCERTAIN", brokerId, maxFilled);
        }
        return new BrokerHistoryAssessment("ACKNOWLEDGED",
            "UPSTOX_FNO_PENDING_ZERO_FILL_VERIFIED", brokerId, 0);
    }

    /** Global emergency stop has no authority to prevent closing a long. */
    static boolean blocksNewLiveEntry(String side, Boolean globalHalted) {
        return "BUY".equals(side) && !Boolean.FALSE.equals(globalHalted);
    }

    private void requireLiveEntryNotHalted(String side, boolean insideTransaction) {
        if (!"BUY".equals(side)) return;
        // Absent/unreadable global control is fail-closed for fresh exposure.
        Boolean halted = jdbc.sql(
                "SELECT new_orders_halted FROM trading_control WHERE id=1"
                    + (insideTransaction ? " FOR SHARE" : ""))
            .query(Boolean.class).optional().orElse(null);
        if (blocksNewLiveEntry(side, halted)) {
            throw error(HttpStatus.CONFLICT,
                "Global emergency stop blocks new LIVE option purchases");
        }
    }

    private Room requireRoom(UUID owner, UUID room) {
        if (owner == null || room == null) throw error(HttpStatus.UNAUTHORIZED, "Customer required");
        Room result = jdbc.sql("""
            SELECT r.focus,r.planned_budget_inr,b.broker
            FROM customer_trading_room r
            LEFT JOIN customer_live_room_broker b
              ON b.room_id=r.id AND b.user_id=r.user_id
            WHERE r.id=:room AND r.user_id=:owner
              AND r.mode='LIVE' AND r.market_scope IN ('FNO','ALL')
            """)
            .param("room", room).param("owner", owner)
            .query((rs,n) -> new Room(rs.getString("focus"),
                rs.getLong("planned_budget_inr"),rs.getString("broker")))
            .optional().orElseThrow(() -> error(HttpStatus.NOT_FOUND,
                "Owner LIVE F&O room not found"));
        return result;
    }

    private Receipt byIdem(UUID owner, UUID room, String key) {
        return jdbc.sql("""
            SELECT id,room_id,instrument_key,expiry,side,quantity,limit_price,
                   state,order_tag,broker_order_id,outcome_code,created_at
            FROM customer_upstox_fno_live_order
            WHERE user_id=:owner AND room_id=:room AND idempotency_key=:idem
            """).param("owner",owner).param("room",room).param("idem",key)
            .query((rs,n) -> receipt(rs)).optional().orElse(null);
    }

    private Receipt byId(UUID owner, UUID room, UUID id) {
        return jdbc.sql("""
            SELECT id,room_id,instrument_key,expiry,side,quantity,limit_price,
                   state,order_tag,broker_order_id,outcome_code,created_at
            FROM customer_upstox_fno_live_order
            WHERE id=:id AND user_id=:owner AND room_id=:room
            """).param("owner",owner).param("room",room).param("id",id)
            .query((rs,n) -> receipt(rs)).optional().orElse(null);
    }

    private static Receipt receipt(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Receipt(rs.getObject("id",UUID.class),
            rs.getObject("room_id",UUID.class), rs.getString("instrument_key"),
            rs.getObject("expiry",LocalDate.class),rs.getString("side"),
            rs.getInt("quantity"),rs.getBigDecimal("limit_price"),
            rs.getString("state"),rs.getString("order_tag"),
            rs.getString("broker_order_id"),rs.getString("outcome_code"),
            rs.getTimestamp("created_at").toInstant());
    }

    private Receipt matching(Receipt existing, String expected, UUID owner,
            UUID room, String key) {
        String actual = jdbc.sql("""
            SELECT payload_hash FROM customer_upstox_fno_live_order
            WHERE user_id=:owner AND room_id=:room AND idempotency_key=:key
            """).param("owner", owner).param("room", room).param("key", key)
            .query(String.class).single();
        if (!expected.equals(actual)) {
            throw error(HttpStatus.CONFLICT, "LIVE idempotency payload mismatch");
        }
        return existing;
    }

    /** All outstanding external orders block new LIVE exposure, across instruments. */
    static boolean hasUnsettledBrokerOrders(JsonNode book) {
        if (book == null || !book.isArray() || book.size() > 2000) return true;
        for (JsonNode row : book) {
            if (row == null || !row.isObject()) return true;
            String state = row.path("status").asText("").toLowerCase(Locale.ROOT);
            if (!List.of("complete", "cancelled", "canceled", "rejected")
                    .contains(state)) return true;
        }
        return false;
    }

    /** Unreconciled derivatives exposure, including a short, blocks new BUY. */
    static boolean hasExistingNfoExposure(JsonNode positions) {
        if (positions == null || !positions.isArray() || positions.size() > 2000)
            return true;
        for (JsonNode row : positions) {
            if (row == null || !row.isObject()) return true;
            String exchange = row.path("exchange").asText("");
            if (exchange.isBlank()) return true;
            if ("NFO".equalsIgnoreCase(exchange)) {
                JsonNode quantity = row.path("quantity");
                if (!quantity.canConvertToInt() || quantity.asInt() != 0)
                    return true;
            }
        }
        return false;
    }

    static boolean hasOpenOrder(JsonNode book, String instrument) {
        if (book == null || !book.isArray() || book.size() > 2000) return true;
        for (JsonNode row : book) {
            if (!instrument.equals(row.path("instrument_token").asText(""))) continue;
            String status = row.path("status").asText("").toLowerCase(Locale.ROOT);
            if (!List.of("complete","cancelled","canceled","rejected")
                    .contains(status)) return true;
        }
        return false;
    }

    static int availableLongUnits(JsonNode positions, String instrument,
            String product) {
        if (positions == null || !positions.isArray() || positions.size() > 2000) return 0;
        int available = 0;
        for (JsonNode position : positions) {
            if (!instrument.equals(position.path("instrument_token").asText(""))
                    || !"NFO".equals(position.path("exchange").asText(""))
                    || !product.equals(position.path("product").asText(""))
                    || !position.path("quantity").canConvertToInt()) continue;
            int quantity = position.path("quantity").asInt();
            if (quantity < 0) return 0;
            available = Math.addExact(available,quantity);
        }
        return available;
    }

    private static void validateRequest(UUID owner, UUID room, String idem, Request request) {
        if (owner == null || room == null || request == null
                || idem == null || !idem.matches("[A-Za-z0-9_-]{8,64}")
                || request.instrumentKey() == null
                || !request.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || request.expiry() == null
                || !List.of("BUY","SELL").contains(request.side())
                || request.lots() != 1 || request.limitPrice() == null
                || request.limitPrice().signum() <= 0
                || request.limitPrice().scale() > 4
                || request.limitPrice().compareTo(new BigDecimal("1000000")) > 0) {
            throw error(HttpStatus.BAD_REQUEST, "Invalid one-lot NFO LIVE order");
        }
    }

    private static String fingerprint(Request request) {
        String value = request.instrumentKey() + "|" + request.expiry()
            + "|" + request.side() + "|" + request.lots() + "|"
            + request.limitPrice().stripTrailingZeros().toPlainString();
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256 unavailable");
        }
    }

    private static long toPaise(BigDecimal value) {
        return value.multiply(HUNDRED).setScale(0,RoundingMode.UP).longValueExact();
    }

    /** Broker intraday auto-squareoff is earlier than the F&O session close.
     * Prevent new I-product orders at/after 15:20 even though NSE_FO
     * NORMAL_CLOSE is 15:40; carry-forward D uses the actual segment close. */
    static boolean insideUpstoxIntradayCutoff(Instant when, String roomFocus) {
        if (when == null) return false;
        return !"INTRADAY".equals(roomFocus)
            || when.atZone(IST).toLocalTime().isBefore(
                java.time.LocalTime.of(15, 20));
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
