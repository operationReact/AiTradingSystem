package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import in.aitradingsystem.provider.upstox.UpstoxFnoLimitOrderPayload;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mintick Mind F&O LIVE proposal boundary. Read-only preflight, not execution.
 *
 * The existing Upstox LIVE coordinator remains the only order authority:
 * this service never calls submit(), order place, cancel or broker reconcile.
 * Any future LIVE order must independently pass the coordinator's fresh
 * revalidation and authenticated customer per-order confirmation.
 *
 * A research crossover is not an established profitable trading edge.
 * Dry-run validation means that the request's evidence and risk envelope
 * passed checks at one instant; it is NOT permission to trade.
 */
@Service
public final class CustomerMindFnoLiveProposalService {
    private static final String NIFTY = "NSE_INDEX|Nifty 50";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration MAX_AGE = Duration.ofSeconds(15);
    private static final BigDecimal MAX_PILOT_PREMIUM = new BigDecimal("25000");
    private static final BigDecimal MARGIN_USE_FRACTION = new BigDecimal("0.80");
    private static final Set<String> VALID_STRATEGIES =
        Set.of("strategy.STR-181", "strategy.STR-182");

    private final CustomerMindFnoResearchController research;
    private final CustomerUpstoxFnoLiveOrderService live;
    private final CustomerUpstoxOAuthService broker;
    private final UpstoxAnalyticsSource source;
    private final ObjectProvider<UpstoxFnoV3LiveFeedService> feeds;
    private final UpstoxFnoLimitOrderPayload payloads;
    private final JdbcClient jdbc;
    private final Clock clock;

    public record Proposal(String contractVersion, UUID roomId,
            Instant evaluatedAt, String state, List<String> reasons,
            String strategyId, String signal, String candleSource,
            Instant latestCompletedCloseAt, String quoteSource,
            String instrumentKey, LocalDate expiry, BigDecimal strike,
            int lots, Integer units, BigDecimal bid, BigDecimal ask,
            BigDecimal limitPrice, BigDecimal maximumPremiumInr,
            BigDecimal premiumInr, BigDecimal estimatedCostsBufferInr,
            BigDecimal stopReference, BigDecimal targetReference,
            BigDecimal verifiedAvailableMarginInr, Instant exchangeAt,
            Instant observedAt, String proposalId,
            boolean executionAuthorized, boolean orderPlaced) {
        public Proposal {
            reasons = List.copyOf(reasons);
        }
    }

    public record OutcomeSimulation(String state, boolean needsBrokerReconciliation,
            boolean mayRetryAutomatically, int filledQuantity) {}

    record Room(String focus, String riskPreference,
            long plannedBudgetInr, String marketScope) {}

    public CustomerMindFnoLiveProposalService(CustomerMindFnoResearchController research,
            CustomerUpstoxFnoLiveOrderService live, CustomerUpstoxOAuthService broker,
            UpstoxAnalyticsSource source,
            ObjectProvider<UpstoxFnoV3LiveFeedService> feeds,
            UpstoxFnoLimitOrderPayload payloads, JdbcClient jdbc, Clock clock) {
        this.research=research;
        this.live=live;
        this.broker=broker;
        this.source=source;
        this.feeds=feeds;
        this.payloads=payloads;
        this.jdbc=jdbc;
        this.clock=clock;
    }

    public Proposal inspect(UUID owner, UUID roomId, Authentication authentication) {
        Instant now=clock.instant();
        Room room=requireOwnerLiveRoom(owner,roomId);
        if (room.focus()==null || !Set.of("INTRADAY","SWING").contains(room.focus())
                || !"FNO".equals(room.marketScope())
                || room.riskPreference()==null
                || !Set.of("CAUTIOUS","BALANCED","AGGRESSIVE")
                    .contains(room.riskPreference())
                || room.plannedBudgetInr()<1000) {
            return blocked(roomId,now,"LIVE_FNO_ROOM_RISK_POLICY_UNSUPPORTED");
        }
        // The broker coordinator is the source of truth for customer session,
        // permissions, registered static IP, server policy and session.
        final CustomerUpstoxFnoLiveOrderService.Capability cap;
        try {
            cap=live.capability(owner,roomId);
        } catch (ResponseStatusException unauthorized) {
            throw unauthorized;
        } catch (RuntimeException unavailable) {
            return blocked(roomId,now,"LIVE_ORDER_CAPABILITY_UNAVAILABLE");
        }
        if (cap==null) return blocked(roomId,now,"LIVE_ORDER_CAPABILITY_UNAVAILABLE");
        if (!cap.eligibleForOrderPreflight()) {
            return new Proposal("mind.fno-live-proposal.v1",roomId,now,
                "BLOCKED",cap.reasons().isEmpty()
                    ? List.of("LIVE_ORDER_PREFLIGHT_NOT_READY") : cap.reasons(),
                null,"NO_TRADE",null,null,null,null,null,null,
                0,null,null,null,null,null,null,null,
                null,null,null,null,null,null,false,false);
        }
        var signal=research.inspect(authentication);
        if (signal==null || !"RESEARCH_CANDIDATE".equals(signal.status())) {
            return blocked(roomId,now,signal==null
                ? "MIND_FNO_SIGNAL_UNAVAILABLE" : "MIND_FNO_"+safeReason(signal.reasonCode()));
        }
        String expectedSignal="strategy.STR-181".equals(signal.strategyId())
            ? "BUY_CALL_RESEARCH" : "BUY_PUT_RESEARCH";
        if (signal.strategyId()==null || !VALID_STRATEGIES.contains(signal.strategyId())
                || !expectedSignal.equals(signal.signal())
                || !NIFTY.equals(signal.underlyingKey())
                || !"UPSTOX_V3_EXCHANGE_TIMED".equals(signal.quoteSource())
                || signal.candleSource()==null
                || !Set.of("ANGEL_ONE_SMARTAPI","UPSTOX_ANALYTICS")
                    .contains(signal.candleSource())
                || signal.completedCandles()<35
                || signal.latestCompletedCloseAt()==null
                || signal.latestCompletedCloseAt().isAfter(now)
                || signal.exchangeAt()==null || signal.observedAt()==null
                || !recent(signal.exchangeAt(),now)
                || !recent(signal.observedAt(),now)
                || signal.observedAt().isBefore(signal.exchangeAt())
                || signal.instrumentKey()==null
                || !signal.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || signal.expiry()==null || signal.strike()==null
                || signal.lotSize()==null || signal.lotSize()<1
                || signal.lotSize()>10000) {
            return blocked(roomId,now,"MIND_FNO_SIGNAL_PROVENANCE_INVALID");
        }
        LocalDate today=now.atZone(IST).toLocalDate();
        if (signal.expiry().isBefore(today) || signal.expiry().isAfter(today.plusDays(90))) {
            return blocked(roomId,now,"NFO_LISTED_EXPIRY_OUT_OF_RANGE");
        }
        final UpstoxAnalyticsSource.FnoOptionContract listed;
        try {
            listed=source.currentFnoOptionContracts(NIFTY,signal.expiry()).stream()
                .filter(c->NIFTY.equals(c.underlyingKey())
                    && signal.instrumentKey().equals(c.instrumentKey())
                    && signal.expiry().equals(c.expiry())
                    && c.strikePrice()!=null
                    && signal.strike().compareTo(c.strikePrice())==0
                    && (("strategy.STR-181".equals(signal.strategyId())&&"CE".equals(c.side()))
                        || ("strategy.STR-182".equals(signal.strategyId())&&"PE".equals(c.side()))))
                .findFirst().orElse(null);
        } catch (RuntimeException unavailable) {
            return blocked(roomId,now,"NFO_LISTING_SOURCE_UNAVAILABLE");
        }
        if (listed==null || listed.lotSize()!=signal.lotSize()
                || listed.tickSize()==null || listed.tickSize().signum()<=0
                || listed.tickSize().scale()>4 || listed.lotSize()<1) {
            return blocked(roomId,now,"NFO_CONTRACT_METADATA_MISMATCH");
        }
        var feed=feeds.getIfAvailable();
        if (feed==null) return blocked(roomId,now,"NFO_EXCHANGE_FEED_UNAVAILABLE");
        var quote=feed.eligiblePaperQuote(signal.instrumentKey());
        if (quote.isEmpty()) return blocked(roomId,now,"NFO_EXCHANGE_QUOTE_NOT_FRESH");
        var latest=quote.get();
        if (latest.bid()==null || latest.ask()==null
                || latest.bid().signum()<=0 || latest.ask().compareTo(latest.bid())<0
                || !recent(latest.exchangeAt(),now) || !recent(latest.observedAt(),now)
                || latest.observedAt().isBefore(latest.exchangeAt())
                || latest.exchangeAt().isBefore(signal.exchangeAt())) {
            return blocked(roomId,now,"NFO_EXCHANGE_QUOTE_PROVENANCE_INVALID");
        }
        final BigDecimal availableMargin;
        final JsonNode brokerBook, positions;
        try {
            JsonNode reported=broker.funds(owner).data()
                .path("equity").path("available_margin");
            availableMargin=reported.isNumber()?reported.decimalValue():null;
            brokerBook=broker.orders(owner).data();
            positions=broker.positions(owner).data();
        } catch (RuntimeException unavailable) {
            return blocked(roomId,now,"OWNER_BROKER_ACCOUNT_SNAPSHOT_UNAVAILABLE");
        }
        if (availableMargin==null || availableMargin.signum()<=0) {
            return blocked(roomId,now,"VERIFIED_BROKER_MARGIN_UNAVAILABLE");
        }
        if (!cleanBrokerBook(brokerBook) || hasNfoExposure(positions)) {
            return blocked(roomId,now,"BROKER_ORDER_OR_POSITION_REQUIRES_RECONCILIATION");
        }
        try {
            long pending=jdbc.sql("""
                SELECT COUNT(*) FROM customer_upstox_fno_live_order
                WHERE user_id=:owner AND state IN ('SUBMITTING','UNKNOWN','ACKNOWLEDGED')
                """).param("owner",owner).query(Long.class).single();
            if (pending!=0) return blocked(roomId,now,"PLATFORM_LIVE_ORDER_RECONCILIATION_PENDING");
            Boolean halted=jdbc.sql("SELECT new_orders_halted FROM trading_control WHERE id=1")
                .query(Boolean.class).optional().orElse(null);
            if (!Boolean.FALSE.equals(halted)) {
                return blocked(roomId,now,"GLOBAL_LIVE_ENTRY_HALTED");
            }
        } catch (RuntimeException unavailable) {
            return blocked(roomId,now,"LIVE_ACCOUNT_RISK_STATE_UNAVAILABLE");
        }
        BigDecimal tick=listed.tickSize();
        BigDecimal limit=ceilTick(latest.ask(),tick);
        if (limit.scale()>4 || limit.compareTo(new BigDecimal("1000000"))>0
                || limit.compareTo(latest.ask().multiply(new BigDecimal("1.02")))>0) {
            return blocked(roomId,now,"NFO_PRICE_OUTSIDE_BROKER_COLLAR");
        }
        BigDecimal premium=limit.multiply(BigDecimal.valueOf(listed.lotSize()));
        BigDecimal estimatedCosts=buffer(premium);
        BigDecimal allowance=maxPremium(room.plannedBudgetInr(),room.riskPreference(),
            availableMargin);
        if (allowance==null || premium.add(estimatedCosts).compareTo(allowance)>0) {
            return blocked(roomId,now,"ONE_LOT_EXCEEDS_LIVE_RISK_BUDGET");
        }

        String proposalId=UUID.nameUUIDFromBytes(
            (owner+"|"+roomId+"|"+signal.strategyId()+"|"
                +signal.latestCompletedCloseAt()+"|"+signal.instrumentKey()+"|"
                +signal.expiry()).getBytes(StandardCharsets.UTF_8))
            .toString();
        // Validate the precise Upstox V3 LIMIT payload shape offline. This
        // builder does not call any transport or persist an order.
        try {
            String temporaryTag="mtpreview"+proposalId.replace("-","").substring(0,8);
            payloads.build(new UpstoxFnoLimitOrderPayload.VerifiedParameters(
                NIFTY,listed.instrumentKey(),listed.side(),today,listed.expiry(),
                listed.lotSize(),listed.tickSize(),1,"BUY",room.focus(),limit,
                0,temporaryTag));
        } catch (RuntimeException invalid) {
            return blocked(roomId,now,"BROKER_LIMIT_PAYLOAD_INVALID");
        }
        return new Proposal("mind.fno-live-proposal.v1",roomId,now,
            "RISK_PASSED_DRY_RUN",List.of(),signal.strategyId(),signal.signal(),
            signal.candleSource(),signal.latestCompletedCloseAt(),signal.quoteSource(),
            signal.instrumentKey(),signal.expiry(),signal.strike(),
            1,listed.lotSize(),latest.bid(),latest.ask(),limit,allowance,
            premium,estimatedCosts,
            floorTick(limit.multiply(new BigDecimal("0.92")),tick),
            ceilTick(limit.multiply(new BigDecimal("1.15")),tick),
            availableMargin,latest.exchangeAt(),latest.observedAt(),proposalId,
            false,false);
    }

    Room requireOwnerLiveRoom(UUID owner,UUID roomId) {
        if (owner==null || roomId==null) throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,"Customer session required");
        return jdbc.sql("""
            SELECT focus,risk_preference,planned_budget_inr,market_scope
            FROM customer_trading_room
            WHERE user_id=:owner AND id=:room AND mode='LIVE'
                AND market_scope='FNO'
            """).param("owner",owner).param("room",roomId)
            .query((rs,n)->new Room(rs.getString("focus"),
                rs.getString("risk_preference"),rs.getLong("planned_budget_inr"),
                rs.getString("market_scope")))
            .optional().orElseThrow(()->new ResponseStatusException(
                HttpStatus.NOT_FOUND,"Owner F&O LIVE room unavailable"));
    }

    private static Proposal blocked(UUID roomId,Instant at,String reason) {
        return new Proposal("mind.fno-live-proposal.v1",roomId,at,"BLOCKED",
            List.of(reason),null,"NO_TRADE",null,null,null,null,null,null,
            0,null,null,null,null,null,null,null,null,
            null,null,null,null,null,false,false);
    }

    private static String safeReason(String value) {
        return value!=null && value.matches("[A-Z0-9_]{1,64}")
            ? value : "RESEARCH_NOT_READY";
    }

    private static boolean recent(Instant at,Instant now) {
        return at!=null && now!=null && !at.isAfter(now)
            && !Duration.between(at,now).isNegative()
            && Duration.between(at,now).compareTo(MAX_AGE)<=0;
    }

    /** Long option premium is the potential at-risk principal, not the stop. */
    static BigDecimal maxPremium(long plannedInr,String risk,BigDecimal availableMargin) {
        if (plannedInr<1000 || availableMargin==null || availableMargin.signum()<=0)
            return null;
        BigDecimal fraction=switch (risk) {
            case "CAUTIOUS" -> new BigDecimal("0.05");
            case "BALANCED" -> new BigDecimal("0.10");
            case "AGGRESSIVE" -> new BigDecimal("0.15");
            default -> BigDecimal.ZERO;
        };
        if (fraction.signum()<=0) return null;
        BigDecimal room=BigDecimal.valueOf(plannedInr);
        return room.multiply(fraction)
            .min(room.divide(BigDecimal.valueOf(3),4,RoundingMode.DOWN))
            .min(MAX_PILOT_PREMIUM)
            .min(availableMargin.multiply(MARGIN_USE_FRACTION))
            .setScale(2,RoundingMode.DOWN);
    }

    private static BigDecimal buffer(BigDecimal premium) {
        // Conservative reservation buffer, not an exact statutory tax quote.
        return new BigDecimal("50").max(
            premium.multiply(new BigDecimal("0.005")))
            .setScale(2,RoundingMode.UP);
    }
    private static BigDecimal ceilTick(BigDecimal value,BigDecimal tick) {
        return value.divide(tick,0,RoundingMode.CEILING).multiply(tick);
    }
    private static BigDecimal floorTick(BigDecimal value,BigDecimal tick) {
        return value.divide(tick,0,RoundingMode.FLOOR).multiply(tick);
    }

    /** Any unsettled broker order, on any instrument, blocks extra exposure. */
    static boolean cleanBrokerBook(JsonNode book) {
        if (book==null || !book.isArray() || book.size()>2000) return false;
        for (JsonNode item:book) {
            String state=item.path("status").asText("").toLowerCase(java.util.Locale.ROOT);
            if (!Set.of("complete","rejected","cancelled","canceled").contains(state))
                return false;
        }
        return true;
    }

    /** Conservative initial pilot: avoid any existing NFO exposure. */
    static boolean hasNfoExposure(JsonNode positions) {
        if (positions==null || !positions.isArray() || positions.size()>2000) return true;
        for (JsonNode item:positions) {
            if (!item.path("exchange").asText("").equals("NFO")) continue;
            if (!item.path("quantity").canConvertToInt()
                    || item.path("quantity").asInt()!=0) return true;
        }
        return false;
    }

    /**
     * Test-only state machine for broker response scenarios; no broker call,
     * no order state updates and NO permission to resend UNKNOWN/PARTIAL.
     */
    static OutcomeSimulation simulateBrokerOutcome(String status,int filled,int requested) {
        if (requested<=0 || filled<0 || filled>requested) {
            return new OutcomeSimulation("UNKNOWN",true,false,Math.max(0,filled));
        }
        if (filled>0 && filled<requested) {
            return new OutcomeSimulation("PARTIAL_NEEDS_RECONCILIATION",true,false,filled);
        }
        if (filled==requested && "complete".equalsIgnoreCase(status)) {
            return new OutcomeSimulation("COMPLETE",false,false,filled);
        }
        if (filled==0 && "rejected".equalsIgnoreCase(status)) {
            return new OutcomeSimulation("REJECTED",false,false,0);
        }
        if (filled==0 && ("cancelled".equalsIgnoreCase(status)
                || "canceled".equalsIgnoreCase(status))) {
            return new OutcomeSimulation("CANCELLED",false,false,0);
        }
        return new OutcomeSimulation("UNKNOWN",true,false,filled);
    }
}
