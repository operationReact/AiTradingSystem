package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedRatioSpreadSourceStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FnoRatioSpreadResearchServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String ACCOUNT="private_ratio_account";
    private static final String PROVIDER="UPSTOX_ANALYTICS";
    private static final String NIFTY="NSE_INDEX|Nifty 50";
    private static final String CALL_LONG="NSE_FO|NIFTY_20261127_120_CE";
    private static final String CALL_SHORT="NSE_FO|NIFTY_20261127_130_CE";
    private static final String PUT_LONG="NSE_FO|NIFTY_20261127_80_PE";
    private static final String PUT_SHORT="NSE_FO|NIFTY_20261127_70_PE";
    private static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    private static BigDecimal n(String v){return new BigDecimal(v);}
    private static MarketSessionCalendar calendar(){
        var c=mock(MarketSessionCalendar.class);
        when(c.isInsideCashSession(NOW)).thenReturn(true);
        when(c.isTradingDay(any(LocalDate.class))).thenAnswer(i->{
            LocalDate day=i.getArgument(0);
            return day.getDayOfWeek().getValue()<=5;
        });
        return c;
    }
    private static List<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose> history(String end){
        var dates=new ArrayList<LocalDate>();LocalDate d=LocalDate.of(2026,11,19);
        while(dates.size()<35){
            if(d.getDayOfWeek().getValue()<=5)dates.add(0,d);
            d=d.minusDays(1);
        }
        var out=new ArrayList<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose>();
        for(int i=0;i<dates.size();i++){
            Instant close=dates.get(i).atTime(15,30).atZone(IST).toInstant();
            out.add(new FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose(
                PROVIDER,NIFTY,dates.get(i),n(i==34?end:"100"),
                close.plusSeconds(60),close));
        }
        return out;
    }
    private static FnoVerifiedRatioSpreadSourceStore.Proof proof(boolean call){
        Instant quote=NOW.minusSeconds(40),known=NOW.minusSeconds(30);
        String l=call?CALL_LONG:PUT_LONG,s=call?CALL_SHORT:PUT_SHORT;
        var longLeg=new FnoVerifiedRatioSpreadSourceStore.OptionLeg(l,
            n(call?"120":"80"),n("0.05"),LocalDate.of(2026,11,1),EXPIRY,50,
            n("5.90"),n("6.00"),10,10,1000,quote,known);
        var shortLeg=new FnoVerifiedRatioSpreadSourceStore.OptionLeg(s,
            n(call?"130":"70"),n("0.05"),LocalDate.of(2026,11,1),EXPIRY,50,
            n("2.70"),n("2.75"),10,10,1000,quote,known);
        var index=new FnoVerifiedRatioSpreadSourceStore.IndexQuote(
            n(call?"125":"75"),n(call?"125.10":"75.10"),quote,known);
        var risk=new FnoVerifiedRatioSpreadSourceStore.Risk(
            50,50,300,2,50,3000,n("10"),n("10000"),10000,
            n(call?"150":"10"),call?5000:500,n("10000"));
        return new FnoVerifiedRatioSpreadSourceStore.Proof(
            PROVIDER,ACCOUNT,call?"CALL":"PUT",NIFTY,longLeg,shortLeg,index,risk,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",known);
    }
    private static FnoVerifiedNiftyLongOptionSourceStore daily(String last){
        var d=mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        when(d.verifiedRecentCloses(NOW)).thenReturn(history(last));
        return d;
    }
    private static FnoVerifiedRatioSpreadSourceStore quotes(){
        var q=mock(FnoVerifiedRatioSpreadSourceStore.class);
        when(q.oneCurrent(ACCOUNT,"CALL",CALL_LONG,CALL_SHORT,NOW))
            .thenReturn(Optional.of(proof(true)));
        when(q.oneCurrent(ACCOUNT,"PUT",PUT_LONG,PUT_SHORT,NOW))
            .thenReturn(Optional.of(proof(false)));
        return q;
    }
    private static FnoRatioSpreadResearchService service(
        FnoVerifiedNiftyLongOptionSourceStore daily,
        FnoVerifiedRatioSpreadSourceStore quotes,boolean rights,String account){
        return new FnoRatioSpreadResearchService(
            daily,quotes,calendar(),rights,rights,rights,rights,
            account,CALL_LONG,CALL_SHORT,PUT_LONG,PUT_SHORT);
    }
    private static java.util.Map<String,FnoFuturesResearchService.StrategyStatus> inspect(
        FnoRatioSpreadResearchService s){
        return s.evaluateAll(NOW).stream().collect(Collectors.toMap(
            FnoFuturesResearchService.StrategyStatus::strategyId,x->x));
    }
    @Test void bullishCallRatioIsOnlyResearchAndNeverClaimedToHaveBoundedUpsideLoss(){
        var q=quotes();var d=daily("125");
        var rows=inspect(service(d,q,true,ACCOUNT));
        assertEquals("RESEARCH_CANDIDATE",rows.get("strategy.STR-190").status());
        assertEquals("CALL_RATIO_UNBOUNDED_RISK_RESEARCH_ONLY",
            rows.get("strategy.STR-190").reasonCode());
        assertEquals("CALL_RATIO_UNBOUNDED_TAIL_RESEARCH_NO_ORDER",
            rows.get("strategy.STR-190").inputState());
        assertEquals("NO_SIGNAL",rows.get("strategy.STR-191").status());
        assertEquals(35,rows.get("strategy.STR-190").historySessions());
        assertEquals(2,rows.get("strategy.STR-190").contractsResolved());
        assertEquals(3,rows.get("strategy.STR-190").freshQuotes());
        verify(d).verifiedRecentCloses(NOW);
        verify(q).oneCurrent(ACCOUNT,"CALL",CALL_LONG,CALL_SHORT,NOW);
        verify(q).oneCurrent(ACCOUNT,"PUT",PUT_LONG,PUT_SHORT,NOW);
    }
    @Test void bearishPutRatioStillKeepsSevereButFiniteDownsideLoss(){
        var rows=inspect(service(daily("75"),quotes(),true,ACCOUNT));
        assertEquals("NO_SIGNAL",rows.get("strategy.STR-190").status());
        assertEquals("RESEARCH_CANDIDATE",rows.get("strategy.STR-191").status());
        assertEquals("PUT_RATIO_FINITE_DOWNWARD_TAIL_RESEARCH_ONLY",
            rows.get("strategy.STR-191").reasonCode());
    }
    @Test void defaultDeniedRightsNeverReadMarketOrBrokerMargin(){
        var d=mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        var q=mock(FnoVerifiedRatioSpreadSourceStore.class);
        var rows=inspect(service(d,q,false,ACCOUNT));
        assertEquals("BLOCKED_RIGHTS",rows.get("strategy.STR-190").status());
        assertEquals("BLOCKED_RIGHTS",rows.get("strategy.STR-191").status());
        verifyNoInteractions(d,q);
    }
    @Test void missingAccountOrExactTwoShortLotProofCannotTrade(){
        var d=daily("125");var q=quotes();
        var noAccount=inspect(service(d,q,true,""));
        assertEquals("RATIO_BROKER_MARGIN_ACCOUNT_NOT_CONFIGURED",
            noAccount.get("strategy.STR-190").reasonCode());
        verify(d,never()).verifiedRecentCloses(NOW);
        when(q.oneCurrent(ACCOUNT,"CALL",CALL_LONG,CALL_SHORT,NOW))
            .thenReturn(Optional.empty());
        var absent=inspect(service(d,q,true,ACCOUNT));
        assertEquals("BLOCKED_DATA",absent.get("strategy.STR-190").status());
        assertEquals("RATIO_TWO_SHORT_LOT_DEPTH_MARGIN_FEES_MISSING",
            absent.get("strategy.STR-190").reasonCode());
    }
    @Test void mismatchedProviderAndInsufficientTwoShortLotBidDepthFailClosed(){
        var q=quotes();var p=proof(true);
        var bad=new FnoVerifiedRatioSpreadSourceStore.OptionLeg(
            p.shortLeg().key(),p.shortLeg().strike(),p.shortLeg().tick(),
            p.shortLeg().listedFrom(),p.shortLeg().expiry(),p.shortLeg().lotSize(),
            p.shortLeg().bid(),p.shortLeg().ask(),1,10,1000,
            p.shortLeg().exchangeAt(),p.shortLeg().firstKnownAt());
        when(q.oneCurrent(ACCOUNT,"CALL",CALL_LONG,CALL_SHORT,NOW)).thenReturn(
            Optional.of(new FnoVerifiedRatioSpreadSourceStore.Proof(
                p.provider(),p.accountScope(),p.ratioSide(),p.indexKey(),
                p.longLeg(),bad,p.index(),p.risk(),p.contractDigest(),p.firstKnownAt())));
        var rows=inspect(service(daily("125"),q,true,ACCOUNT));
        assertEquals("RATIO_PROVIDER_CONTRACT_RISK_OR_TIME_MISMATCH",
            rows.get("strategy.STR-190").reasonCode());
    }
    @Test void staleHistoryAndSourceFailureDoNotLeakTokens(){
        var d=daily("125");
        when(d.verifiedRecentCloses(NOW)).thenReturn(history("125").subList(0,30));
        var denied=inspect(service(d,quotes(),true,ACCOUNT));
        assertEquals("RATIO_35_VERIFIED_CLOSES_MISSING",
            denied.get("strategy.STR-190").reasonCode());
        var q=quotes();
        when(q.oneCurrent(ACCOUNT,"CALL",CALL_LONG,CALL_SHORT,NOW)).thenThrow(
            new IllegalStateException("private broker margin token"));
        var row=inspect(service(daily("125"),q,true,ACCOUNT)).get("strategy.STR-190");
        assertEquals("RATIO_SOURCE_READ_UNAVAILABLE",row.reasonCode());
        assertFalse(row.toString().contains("token"));
    }
}
