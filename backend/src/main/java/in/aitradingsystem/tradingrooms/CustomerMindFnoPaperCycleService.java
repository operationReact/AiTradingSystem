package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-initiated Mind F&O PAPER cycle. Research is NOT itself an order.
 * Applies room/rights/wallet/audit/risk gates before delegating to the existing
 * transactional, idempotent virtual-only long-option engine.
 *
 * One tick of the cycle can ENTER, HOLD or EXIT. Repeated invocation must not
 * open a second position on the same underlying EMA crossover. This service
 * cannot contact Upstox's broker order transport and has no LIVE dependency.
 *
 * This first vertical slice is invoked explicitly; unattended scheduling
 * requires a separate, durable opt-in and lifecycle worker.
 */
@Service
@ConditionalOnProperty(
    name={"minttick.upstox-fno.paper-enabled","minttick.upstox-fno.v3-feed-enabled"},
    havingValue="true")
public class CustomerMindFnoPaperCycleService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime ENTRY_CUTOFF = LocalTime.of(15, 0);
    private static final LocalTime EXIT_CUTOFF = LocalTime.of(15, 20);
    private static final int MAX_PREMIUM_BPS_OF_INITIAL_CASH = 1000; // 10%; 1 lot maximum
    private static final int PROFIT_EXIT_BPS = 1500; // 15% of virtual cost basis
    private static final int LOSS_EXIT_BPS = -800; // -8% of virtual cost basis
    private static final String ENTRY_PREFIX = "mindfno_entry_";
    private static final String EXIT_PREFIX = "mindfno_exit_";

    public record CycleResult(String contractVersion, Instant evaluatedAt, UUID roomId,
        String status, String reasonCode, String action, String strategyId,
        String instrumentKey, UUID fillId, Long realizedPnlDeltaPaise,
        Long walletCashAfterPaise, String orderReconciliation,
        String walletReconciliation, boolean liveMoney) {}

    private final CustomerMindFnoResearchController research;
    private final CustomerPaperFnoOrderService orders;
    private final CustomerPaperWalletService wallets;
    private final CustomerPaperFnoCapabilityController capabilities;
    private final CustomerPaperFnoReconciliationService reconcile;
    private final CustomerPaperSharedWalletAuditService walletAudit;
    private final UpstoxFnoV3LiveFeedService feed;
    private final JdbcClient jdbc;
    private final Clock clock;

    public CustomerMindFnoPaperCycleService(CustomerMindFnoResearchController research,
            CustomerPaperFnoOrderService orders, CustomerPaperWalletService wallets,
            CustomerPaperFnoCapabilityController capabilities,
            CustomerPaperFnoReconciliationService reconcile,
            CustomerPaperSharedWalletAuditService walletAudit,
            UpstoxFnoV3LiveFeedService feed, JdbcClient jdbc, Clock clock) {
        this.research=research; this.orders=orders; this.wallets=wallets;
        this.capabilities=capabilities; this.reconcile=reconcile;
        this.walletAudit=walletAudit; this.feed=feed; this.jdbc=jdbc; this.clock=clock;
    }

    public CycleResult run(UUID owner, UUID room, Authentication auth) {
        return evaluate(owner,room,auth,false);
    }

    /** No JWT reconstruction; scheduler calls only after DB-owned room opt-in. */
    CycleResult runOptedIn(UUID owner,UUID room) {
        return evaluate(owner,room,null,true);
    }

    private CycleResult evaluate(UUID owner,UUID room,Authentication auth,boolean worker) {
        if (owner == null || room == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer required");
        }
        Instant now=clock.instant();
        var capability=worker ? capabilities.internalCapability(owner,room)
            : capabilities.capability(room, auth).getBody();
        if (capability==null || !capability.paperConfigured()) {
            return abstain(now,room,"BLOCKED","FNO_PAPER_CAPABILITY_NOT_READY",null,null,null,null);
        }
        var wallet=wallets.get(owner,room); // owner-scoped funded PAPER room
        if (wallet.halted()) return abstain(now,room,"BLOCKED","PAPER_WALLET_PAUSED",null,null,null,null);
        var audit=walletAudit.inspect(owner,room);
        var books=reconcile.inspect(owner,room);
        if (!audit.cashAndRealizedPnlReconciled() ||
                !List.of("NO_FILLS","BOOKKEEPING_MATCHED").contains(books.status())) {
            return abstain(now,room,"BLOCKED","PAPER_RECONCILIATION_MISMATCH",
                null,null,books.status(),audit.status());
        }
        List<CustomerPaperFnoOrderService.Position> positions=orders.positions(owner,room);
        if (positions.size()>1) {
            return abstain(now,room,"BLOCKED","MULTIPLE_PAPER_OPTION_POSITIONS",
                null,null,books.status(),audit.status());
        }
        if (!positions.isEmpty()) {
            return manageExisting(now,owner,room,positions.getFirst(),books.status(),audit.status());
        }
        if (!now.atZone(IST).toLocalTime().isBefore(ENTRY_CUTOFF)) {
            return abstain(now,room,"NO_TRADE","PAPER_ENTRY_CUTOFF",null,null,books.status(),audit.status());
        }
        var decision=worker ? research.inspectForOwnedPaperWorker()
            : research.inspect(auth); // same verified provider source as Mind UI
        if (decision==null) {
            return abstain(now,room,"BLOCKED","FNO_RESEARCH_RESPONSE_MISSING",
                null,null,books.status(),audit.status());
        }
        if (!"RESEARCH_CANDIDATE".equals(decision.status()) ||
                !"UPSTOX_V3_EXCHANGE_TIMED".equals(decision.quoteSource()) ||
                !List.of("strategy.STR-181","strategy.STR-182").contains(decision.strategyId()) ||
                decision.exchangeAt()==null || decision.observedAt()==null ||
                decision.latestCompletedCloseAt()==null || decision.ask()==null ||
                decision.lotSize()==null || decision.lotSize()<1 || decision.lotSize()>10000 ||
                decision.instrumentKey()==null || decision.expiry()==null ||
                // Independently reject forward-known or stale crossover bars,
                // even if a future research adapter accidentally emits CANDIDATE.
                decision.latestCompletedCloseAt().isAfter(now) ||
                java.time.Duration.between(decision.latestCompletedCloseAt(),now)
                    .compareTo(java.time.Duration.ofDays(4))>0) {
            return abstain(now,room,"NO_TRADE",
                "RESEARCH_CANDIDATE".equals(decision.status()) ?
                    "FNO_SIGNAL_PROVENANCE_INVALID" : decision.reasonCode(),
                decision.strategyId(),decision.instrumentKey(),books.status(),audit.status());
        }
        String expectedSide="strategy.STR-181".equals(decision.strategyId())
            ? "BUY_CALL_RESEARCH" : "BUY_PUT_RESEARCH";
        if (!expectedSide.equals(decision.signal())
                || decision.exchangeAt().isAfter(now) || decision.observedAt().isAfter(now)
                // Provider observations cannot predate their exchange ticks.
                || decision.observedAt().isBefore(decision.exchangeAt())
                || java.time.Duration.between(decision.exchangeAt(),now)
                    .compareTo(java.time.Duration.ofSeconds(15))>0
                || java.time.Duration.between(decision.observedAt(),now)
                    .compareTo(java.time.Duration.ofSeconds(15))>0) {
            return abstain(now,room,"BLOCKED","FNO_RESEARCH_QUOTE_STALE",
                decision.strategyId(),decision.instrumentKey(),books.status(),audit.status());
        }
        String key=entryKey(room,decision.strategyId(),decision.latestCompletedCloseAt());
        if (alreadyUsed(owner,room,key)) {
            return abstain(now,room,"NO_TRADE","MIND_FNO_SIGNAL_ALREADY_TRADED",
                decision.strategyId(),decision.instrumentKey(),books.status(),audit.status());
        }
        if (!premiumWithinBudget(wallet.initialCashPaise(),wallet.availableCashPaise(),
                decision.ask(),decision.lotSize())) {
            return abstain(now,room,"NO_TRADE","MIND_FNO_ONE_LOT_EXCEEDS_RISK_BUDGET",
                decision.strategyId(),decision.instrumentKey(),books.status(),audit.status());
        }
        // The order engine will re-read provider contract metadata and V3 ask,
        // recheck the quote/session, serialize the wallet and enforce idempotency.
        try {
            var fill=orders.submit(owner,room,key,decision.instrumentKey(),
                decision.expiry(),"BUY",1,decision.ask());
            return filled(now,owner,room,"ENTER",decision.strategyId(),fill);
        } catch (ResponseStatusException rejected) {
            return abstain(now,room,"ORDER_REJECTED","EXISTING_FNO_PAPER_ENGINE_REJECTED",
                decision.strategyId(),decision.instrumentKey(),books.status(),audit.status());
        } catch (RuntimeException unknown) {
            // Idempotency key is deterministic; never perform an automatic retry
            // on an unknown order outcome without inspecting the virtual ledger.
            return abstain(now,room,"UNKNOWN","PAPER_ORDER_OUTCOME_UNKNOWN",
                decision.strategyId(),decision.instrumentKey(),null,null);
        }
    }

    private CycleResult manageExisting(Instant now,UUID owner,UUID room,
            CustomerPaperFnoOrderService.Position position,
            String orderAudit,String cashAudit) {
        // A corrupted position or ledger basis must never turn into a SELL.
        // Validate all fields before querying the order history or quotes.
        if (position==null || position.instrumentKey()==null
                || !position.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || (!"CE".equals(position.optionType())
                    && !"PE".equals(position.optionType()))
                || position.expiry()==null || position.lotSize()<=0
                || position.lotSize()>10000 || position.quantity()<=0
                || position.quantity()!=position.lotSize()
                || position.costBasisPaise()<=0) {
            return abstain(now,room,"BLOCKED","UNSUPPORTED_PAPER_POSITION",
                null,position==null ? null : position.instrumentKey(),orderAudit,cashAudit);
        }
        // Guard against closing manually originated or manually modified holdings.
        String latestKey=jdbc.sql("""
                SELECT idempotency_key FROM customer_paper_fno_order
                WHERE user_id=:owner AND room_id=:room AND instrument_key=:key
                ORDER BY created_at DESC,id DESC LIMIT 1
                """).param("owner",owner).param("room",room)
                .param("key",position.instrumentKey())
                .query(String.class).optional().orElse("");
        if (!latestKey.startsWith(ENTRY_PREFIX)) {
            return abstain(now,room,"HOLD","EXISTING_POSITION_NOT_MIND_MANAGED",
                null,position.instrumentKey(),orderAudit,cashAudit);
        }
        if (!position.priceFresh() || position.indicativeUnrealizedPnlPaise()==null) {
            return abstain(now,room,"HOLD","FNO_EXIT_QUOTE_NOT_FRESH",
                null,position.instrumentKey(),orderAudit,cashAudit);
        }
        boolean expiryDay=!position.expiry().isAfter(now.atZone(IST).toLocalDate());
        boolean cutoff=!now.atZone(IST).toLocalTime().isBefore(EXIT_CUTOFF);
        boolean exit=shouldExit(position.costBasisPaise(),
            position.indicativeUnrealizedPnlPaise(),expiryDay,cutoff);
        if (!exit) return abstain(now,room,"HOLD","FNO_TARGET_STOP_NOT_REACHED",
            null,position.instrumentKey(),orderAudit,cashAudit);
        var quote=feed.eligiblePaperQuote(position.instrumentKey());
        if (quote.isEmpty()) return abstain(now,room,"HOLD","FNO_EXIT_QUOTE_NOT_FRESH",
            null,position.instrumentKey(),orderAudit,cashAudit);
        String key=exitKey(room,latestKey);
        try {
            var fill=orders.submit(owner,room,key,position.instrumentKey(),
                position.expiry(),"SELL",1,quote.get().bid());
            return filled(now,owner,room,"EXIT",null,fill);
        } catch (ResponseStatusException rejected) {
            return abstain(now,room,"ORDER_REJECTED","EXISTING_FNO_PAPER_ENGINE_REJECTED",
                null,position.instrumentKey(),orderAudit,cashAudit);
        } catch (RuntimeException unknown) {
            return abstain(now,room,"UNKNOWN","PAPER_EXIT_OUTCOME_UNKNOWN",
                null,position.instrumentKey(),null,null);
        }
    }

    private boolean alreadyUsed(UUID owner,UUID room,String key) {
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM customer_paper_fno_order
                WHERE user_id=:owner AND room_id=:room AND idempotency_key=:key)
            """).param("owner",owner).param("room",room).param("key",key)
            .query(Boolean.class).single();
    }

    private CycleResult filled(Instant now,UUID owner,UUID room,String action,
            String strategy,CustomerPaperFnoOrderService.Fill fill) {
        // A fill is irreversible within virtual PAPER accounting. Report it
        // even if a later audit errors; never claim no fill on audit failure.
        try {
            var book=reconcile.inspect(owner,room);
            var cash=walletAudit.inspect(owner,room);
            boolean matched="BOOKKEEPING_MATCHED".equals(book.status())
                && cash.cashAndRealizedPnlReconciled();
            return new CycleResult("mind.fno-paper-cycle.v1",now,room,
                matched?"FILLED_RECONCILED":"FILLED_AUDIT_MISMATCH",
                matched?"PAPER_FILL_RECONCILED":"PAPER_POST_FILL_AUDIT_MISMATCH",
                action,strategy,fill.instrumentKey(),fill.id(),fill.realizedPnlDeltaPaise(),
                fill.cashAfterPaise(),book.status(),cash.status(),false);
        } catch (RuntimeException unknown) {
            return new CycleResult("mind.fno-paper-cycle.v1",now,room,
                "FILLED_AUDIT_UNKNOWN","PAPER_POST_FILL_AUDIT_UNAVAILABLE",action,
                strategy,fill.instrumentKey(),fill.id(),fill.realizedPnlDeltaPaise(),
                fill.cashAfterPaise(),null,null,false);
        }
    }

    static boolean premiumWithinBudget(long initialPaise,long cashPaise,
            BigDecimal oneUnitAsk,int lotSize) {
        if (initialPaise<=0 || cashPaise<=0 || oneUnitAsk==null
                || oneUnitAsk.signum()<=0 || lotSize<1 || lotSize>10000) return false;
        try {
            long notional=oneUnitAsk.multiply(BigDecimal.valueOf(lotSize))
                .multiply(BigDecimal.valueOf(100)).setScale(0,RoundingMode.HALF_UP)
                .longValueExact();
            long estimatedFees=Math.max(1,Math.addExact(notional,1999)/2000);
            long cost=Math.addExact(notional,estimatedFees);
            return cost<=cashPaise
                && cost<=Math.multiplyExact(initialPaise,MAX_PREMIUM_BPS_OF_INITIAL_CASH)/10000;
        } catch (ArithmeticException ex) {
            return false;
        }
    }

    static boolean shouldExit(long basisPaise,long indicativePnlPaise,
            boolean expiryDay,boolean nearClose) {
        if(basisPaise<=0) return false; // invalid basis must be blocked by manageExisting
        // Use BigDecimal to avoid integer overflow; estimated P&L includes sell fees.
        BigDecimal bps=BigDecimal.valueOf(indicativePnlPaise)
            .multiply(BigDecimal.valueOf(10000))
            .divide(BigDecimal.valueOf(basisPaise),6,RoundingMode.HALF_UP);
        return expiryDay || nearClose
            || bps.compareTo(BigDecimal.valueOf(PROFIT_EXIT_BPS))>=0
            || bps.compareTo(BigDecimal.valueOf(LOSS_EXIT_BPS))<=0;
    }

    private static String entryKey(UUID room,String strategy,Instant signalBar) {
        return ENTRY_PREFIX+uuidHash(room+"|"+strategy+"|"+signalBar);
    }
    private static String exitKey(UUID room,String entryKey) {
        return EXIT_PREFIX+uuidHash(room+"|"+entryKey);
    }
    private static String uuidHash(String input) {
        return UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8))
            .toString().replace("-","");
    }
    private static CycleResult abstain(Instant now,UUID room,String status,
            String reason,String strategy,String instrumentKey,String books,String cash) {
        return new CycleResult("mind.fno-paper-cycle.v1",now,room,status,reason,
            "NONE",strategy,instrumentKey,null,null,null,books,cash,false);
    }
}
