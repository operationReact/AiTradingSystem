package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static in.aitradingsystem.tradingrooms.FnoDeltaHedgeV1.INDEX;
import in.aitradingsystem.tradingrooms.FnoDeltaHedgeV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoDeltaHedgeV1Test {
    private static final Instant ASOF = Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant QUOTED = Instant.parse("2026-11-20T04:59:20Z");
    private static final Instant KNOWN = Instant.parse("2026-11-20T04:59:24Z");
    private static final Instant BROKER = Instant.parse("2026-11-19T09:00:00Z");
    private static final String SRC = "UPSTOX_ANALYTICS";
    private static final String CEKEY = "NSE_FO|NIFTY_20261127_100_CE";
    private static final String PEKEY = "NSE_FO|NIFTY_20261127_100_PE";
    private static final String FUTKEY = "NSE_FO|NIFTY_20261126_FUT";
    private static BigDecimal n(String value) { return new BigDecimal(value); }

    private static Instrument ce() {
        return new Instrument(CEKEY, INDEX, "CE", n("100"), n("0.05"),
                LocalDate.of(2026,11,1), LocalDate.of(2026,11,27), 50, true);
    }
    private static Instrument pe() {
        return new Instrument(PEKEY, INDEX, "PE", n("100"), n("0.05"),
                LocalDate.of(2026,11,1), LocalDate.of(2026,11,27), 50, true);
    }
    private static FutureInstrument future() {
        return new FutureInstrument(FUTKEY, INDEX, n("0.05"),
                LocalDate.of(2026,11,1), LocalDate.of(2026,11,26), 50, true);
    }
    private static List<OptionPosition> options() {
        return List.of(new OptionPosition(ce(),1,BROKER,true),
                       new OptionPosition(pe(),1,BROKER,true));
    }
    private static FuturesPosition heldFutures(int signedLots) {
        return new FuturesPosition(signedLots, BROKER, true);
    }
    private static OptionQuote call(String delta) {
        return new OptionQuote(SRC,CEKEY,n("4.95"),n("5.05"),n(delta),1000,QUOTED,KNOWN,true);
    }
    private static OptionQuote put(String delta) {
        return new OptionQuote(SRC,PEKEY,n("4.95"),n("5.05"),n(delta),1000,QUOTED,KNOWN,true);
    }
    private static List<OptionQuote> quotes() {
        return List.of(call("0.8"), put("-0.1"));
    }
    private static Snapshot snap(List<OptionQuote> q, long bidDepth,
                                 long askDepth, Instant at, Instant known, String source) {
        return new Snapshot(source, INDEX, FUTKEY, n("100"), n("100"),
                n("100.05"),bidDepth,askDepth,q,at,known,true);
    }
    private static Snapshot snapshot() {
        return snap(quotes(),10,10,QUOTED,KNOWN,SRC);
    }
    private static Policy policy() {
        return new Policy(180,30,250,300,10,10,10,10,500,n("25"),n("5"));
    }
    private static Rights rights() {
        return new Rights(true,true,true,true,true,true);
    }
    private static List<CompletedClose> history() {
        List<LocalDate> sessions = new ArrayList<>();
        LocalDate d = LocalDate.of(2026,11,19);
        while(sessions.size() < 35) {
            if (d.getDayOfWeek().getValue() <= 5) sessions.add(0,d);
            d = d.minusDays(1);
        }
        List<CompletedClose> out = new ArrayList<>();
        for (LocalDate session : sessions) {
            Instant close = session.atTime(LocalTime.of(15,30))
                    .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            out.add(new CompletedClose(session,SRC,n("100"),close,
                    close.plusSeconds(60),true));
        }
        return out;
    }
    private static Decision eval() {
        return run(options(),future(),heldFutures(0),snapshot(),history(),rights(),policy(),ASOF);
    }
    private static Decision run(List<OptionPosition> positions, FutureInstrument future,
                                FuturesPosition held, Snapshot s,
                                List<CompletedClose> bars, Rights r, Policy p, Instant asOf) {
        return FnoDeltaHedgeV1.evaluate(positions,future,held,s,bars,r,p,asOf);
    }
    private static void neverExecutable(Decision d) {
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.paperTradeAuthorized());
        assertFalse(d.liveTradeAuthorized());
        assertTrue(d.sourceBasedResearchOnly());
    }
    @Test void signedIntegerSellHedgeResearchNotTrade() {
        Decision d = eval();
        assertEquals(Status.RESEARCH_REBALANCE,d.status());
        assertEquals("strategy.STR-204",d.strategyId());
        assertEquals(0,d.observedFuturesLots());
        assertEquals(-1,d.hypotheticalTargetFuturesLots());
        assertEquals(-1,d.hypotheticalChangeFuturesLots());
        assertEquals(0,n("35").compareTo(d.optionDeltaUnits()));
        assertEquals(0,n("-15").compareTo(d.hypotheticalResidualDeltaUnits()));
        assertTrue(d.hypotheticalOneWayCostInr().signum() > 0);
        neverExecutable(d);
    }
    @Test void alreadyHedgedDoesNotGenerateNewChange() {
        Decision d=run(options(),future(),heldFutures(-1),snapshot(),history(),rights(),policy(),ASOF);
        assertEquals(Status.NO_REBALANCE,d.status());
        assertEquals(0,d.hypotheticalChangeFuturesLots());
        neverExecutable(d);
    }
    @Test void allSourceRightsRequiredIndependently() {
        var r = new Rights(true,true,true,false,true,true);
        Decision d=run(options(),future(),heldFutures(0),snapshot(),history(),r,policy(),ASOF);
        assertEquals(Status.BLOCKED_RIGHTS,d.status());
        neverExecutable(d);
    }
    @Test void brokerPositionMustBeVerifiedAndKnownBeforeMarketSnapshot() {
        var bad=List.of(new OptionPosition(ce(),1,BROKER,false),options().get(1));
        Decision d=run(bad,future(),heldFutures(0),snapshot(),history(),rights(),policy(),ASOF);
        assertEquals(Status.BLOCKED_DATA,d.status());
        var futureKnown=List.of(new OptionPosition(ce(),1,ASOF,true),options().get(1));
        assertEquals(Status.BLOCKED_DATA,
                run(futureKnown,future(),heldFutures(0),snapshot(),history(),rights(),policy(),ASOF).status());
    }
    @Test void futureKnownOrUnoriginalGreeksAreRejected() {
        var q=new OptionQuote(SRC,CEKEY,n("4.95"),n("5.05"),n("0.8"),1000,
                QUOTED,ASOF.plusSeconds(1),true);
        var s=snap(List.of(q,put("-0.1")),10,10,QUOTED,KNOWN,SRC);
        assertEquals(Status.BLOCKED_DATA,
                run(options(),future(),heldFutures(0),s,history(),rights(),policy(),ASOF).status());
        var synthetic=new OptionQuote(SRC,CEKEY,n("4.95"),n("5.05"),n("0.8"),1000,QUOTED,KNOWN,false);
        s=snap(List.of(synthetic,put("-0.1")),10,10,QUOTED,KNOWN,SRC);
        assertEquals(Status.BLOCKED_DATA,
                run(options(),future(),heldFutures(0),s,history(),rights(),policy(),ASOF).status());
    }
    @Test void originalQuoteMustMatchSourceSnapshotTime() {
        var stale=new OptionQuote(SRC,CEKEY,n("4.95"),n("5.05"),n("0.8"),1000,
                QUOTED.minusSeconds(120),KNOWN,true);
        var s=snap(List.of(stale,put("-0.1")),10,10,QUOTED,KNOWN,SRC);
        assertEquals(Status.BLOCKED_DATA,
                run(options(),future(),heldFutures(0),s,history(),rights(),policy(),ASOF).status());
    }
    @Test void perQuoteProviderCannotBeSilentlyMixedAcrossBrokers() {
        var foreign=new OptionQuote("ANGEL_ONE_SMARTAPI",CEKEY,n("4.95"),n("5.05"),
                n("0.8"),1000,QUOTED,KNOWN,true);
        var s=snap(List.of(foreign,put("-0.1")),10,10,QUOTED,KNOWN,SRC);
        Decision d=run(options(),future(),heldFutures(0),s,history(),rights(),policy(),ASOF);
        assertEquals(Status.BLOCKED_DATA,d.status());
        neverExecutable(d);
    }
    @Test void shortsRequireHypotheticalBuyUsingTrueAskDepth() {
        var shorts=List.of(new OptionPosition(ce(),-1,BROKER,true),
                new OptionPosition(pe(),-1,BROKER,true));
        var d=run(shorts,future(),heldFutures(0),
                snap(quotes(),10,1,QUOTED,KNOWN,SRC),history(),rights(),policy(),ASOF);
        assertEquals(Status.RESEARCH_REBALANCE,d.status());
        assertEquals(1,d.hypotheticalChangeFuturesLots());
        assertTrue(d.hypotheticalOneWayCostInr().signum()>0);
        neverExecutable(d);
        var noAsk=snap(quotes(),10,0,QUOTED,KNOWN,SRC);
        assertEquals(Status.BLOCKED_DATA,
                run(shorts,future(),heldFutures(0),noAsk,history(),rights(),policy(),ASOF).status());
    }
    @Test void missingDuplicateOrUnknownKeysFailClosed() {
        var missing=snap(List.of(call("0.8")),10,10,QUOTED,KNOWN,SRC);
        assertEquals(Status.BLOCKED_DATA,
                run(options(),future(),heldFutures(0),missing,history(),rights(),policy(),ASOF).status());
        var dup=snap(List.of(call("0.8"),call("0.8")),10,10,QUOTED,KNOWN,SRC);
        assertEquals(Status.BLOCKED_DATA,
                run(options(),future(),heldFutures(0),dup,history(),rights(),policy(),ASOF).status());
    }
    @Test void crossSourceHistoryIsDisallowed() {
        var s=snap(quotes(),10,10,QUOTED,KNOWN,"ANGEL_ONE_SMARTAPI");
        assertEquals(Status.BLOCKED_DATA,
                run(options(),future(),heldFutures(0),s,history(),rights(),policy(),ASOF).status());
    }
    @Test void originalDeltaSignCannotBeFlippedOrInferred() {
        var s=snap(List.of(call("-0.8"),put("-0.1")),10,10,QUOTED,KNOWN,SRC);
        assertEquals(Status.BLOCKED_DATA,
                run(options(),future(),heldFutures(0),s,history(),rights(),policy(),ASOF).status());
    }
    @Test void sellSideMustHaveActualBidDepth() {
        var s=snap(quotes(),0,10,QUOTED,KNOWN,SRC);
        assertEquals(Status.BLOCKED_DATA,
                run(options(),future(),heldFutures(0),s,history(),rights(),policy(),ASOF).status());
        s=snap(quotes(),1,10,QUOTED,KNOWN,SRC);
        assertEquals(Status.RESEARCH_REBALANCE,
                run(options(),future(),heldFutures(0),s,history(),rights(),policy(),ASOF).status());
    }
    @Test void costBudgetRejectsEvenAuthenticHedgeCandidate() {
        var p=new Policy(180,30,250,300,10,10,300,300,1,n("25"),n("500"));
        Decision d=run(options(),future(),heldFutures(0),snapshot(),history(),rights(),p,ASOF);
        assertEquals(Status.NO_REBALANCE,d.status());
        assertEquals(0,d.hypotheticalChangeFuturesLots());
        assertTrue(d.hypotheticalOneWayCostInr().signum()>0);
        neverExecutable(d);
    }
    @Test void futuresRiskLimitIsHardBlock() {
        var p=new Policy(180,30,250,300,10,10,10,10,500,n("25"),n("5"));
        var huge=List.of(new OptionPosition(ce(),100,BROKER,true),
                new OptionPosition(pe(),100,BROKER,true));
        Decision d=run(huge,future(),heldFutures(0),snapshot(),history(),rights(),p,ASOF);
        assertEquals(Status.BLOCKED_DATA,d.status());
        assertEquals("DELTA_HEDGE_POSITION_LIMIT_EXCEEDED",d.reasonCode());
    }
    @Test void staleSnapshotAndInsufficientHistoryAreBlocked() {
        var older=snap(quotes(),10,10,QUOTED.minusSeconds(300),
                KNOWN.minusSeconds(300),SRC);
        assertEquals(Status.BLOCKED_DATA,
                run(options(),future(),heldFutures(0),older,history(),rights(),policy(),ASOF).status());
        assertEquals(Status.BLOCKED_DATA,
                run(options(),future(),heldFutures(0),snapshot(),
                        history().subList(0,34),rights(),policy(),ASOF).status());
    }
    @Test void noDeltaMeansNoResearchRebalance() {
        var flat=snap(List.of(call("0.5"),put("-0.5")),10,10,QUOTED,KNOWN,SRC);
        Decision d=run(options(),future(),heldFutures(0),flat,history(),rights(),policy(),ASOF);
        assertEquals(Status.NO_REBALANCE,d.status());
        assertEquals(0,d.hypotheticalChangeFuturesLots());
        neverExecutable(d);
    }
    @Test void tooLargeIntegerLotResidualIsNotAdvertised() {
        var s=snap(List.of(call("0.6"),put("-0.1")),10,10,QUOTED,KNOWN,SRC);
        var p=new Policy(180,30,250,300,10,10,10,10,500,n("10"),n("5"));
        Decision d=run(options(),future(),heldFutures(0),s,history(),rights(),p,ASOF);
        assertEquals(Status.NO_REBALANCE,d.status());
        assertEquals(0,d.hypotheticalChangeFuturesLots());
        neverExecutable(d);
    }
    @Test void missingFuturesPositionProofBlocksInsteadOfAssumingFlat() {
        var unverified=new FuturesPosition(0,BROKER,false);
        Decision d=run(options(),future(),unverified,snapshot(),history(),rights(),policy(),ASOF);
        assertEquals(Status.BLOCKED_DATA,d.status());
        neverExecutable(d);
    }
}
