package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyShortStrangleSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyShortStrangleSourceStore.Proof;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyShortStrangleSourceStore.Leg;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyShortStrangleSourceStore.Budget;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyShortStrangleSourceStore.Index;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Synthetic fixture tests only. They are not independent NSE market E2E proof. */
class FnoShortStrangleResearchServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String CE="NSE_FO|TEST_20261127_105_CE";
    private static final String PE="NSE_FO|TEST_20261127_95_PE";
    private static final String SOURCE="UPSTOX_ANALYTICS";
    private static final String ACCOUNT="personal_margin_account";
    private static BigDecimal d(String s){return new BigDecimal(s);}
    private static MarketSessionCalendar calendar(){
        var c=mock(MarketSessionCalendar.class);
        when(c.isInsideCashSession(NOW)).thenReturn(true);
        when(c.isTradingDay(any(LocalDate.class))).thenAnswer(i->{
            LocalDate day=i.getArgument(0);
            return day.getDayOfWeek().getValue()<=5;
        });
        return c;
    }
    private static List<VerifiedClose> history(){
        var days=new ArrayList<LocalDate>();var date=LocalDate.of(2026,11,19);
        while(days.size()<35){
            if(date.getDayOfWeek().getValue()<=5)days.add(0,date);
            date=date.minusDays(1);
        }
        var out=new ArrayList<VerifiedClose>();
        for(var day:days){
            var close=day.atTime(15,30).atZone(IST).toInstant();
            out.add(new VerifiedClose(SOURCE,"NSE_INDEX|Nifty 50",day,
                d("100"),close.plusSeconds(60),close));
        }
        return out;
    }
    private static Proof proof(){
        var exchange=NOW.minusSeconds(40);
        var firstKnown=NOW.minusSeconds(20);
        return new Proof(SOURCE,ACCOUNT,d("105"),d("95"),d("0.05"),LocalDate.of(2026,11,1),
            LocalDate.of(2026,11,27),50,
            new Index(d("100"),d("100.1"),exchange,firstKnown),
            new Leg(CE,d("1.95"),d("2.00"),d("5"),4,1000,exchange,firstKnown),
            new Leg(PE,d("1.95"),d("2.00"),d("5"),4,1000,exchange,firstKnown),
            new Budget(0,500,1,1,1000,80,d("15"),d("10000"),5000,10000,d("10000"),d("150")),
            firstKnown);
    }
    private static FnoVerifiedNiftyLongOptionSourceStore daily(){
        var s=mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        when(s.verifiedRecentCloses(NOW)).thenReturn(history());
        return s;
    }
    private static FnoVerifiedNiftyShortStrangleSourceStore ivStore(){
        var s=mock(FnoVerifiedNiftyShortStrangleSourceStore.class);
        when(s.oneCurrent(ACCOUNT,CE,PE,NOW)).thenReturn(Optional.of(proof()));
        return s;
    }
    private static FnoShortStrangleResearchService service(
        FnoVerifiedNiftyLongOptionSourceStore daily,
        FnoVerifiedNiftyShortStrangleSourceStore iv,boolean rights){
        return new FnoShortStrangleResearchService(daily,iv,calendar(),
            rights,rights,rights,rights,ACCOUNT,rights,CE,PE);
    }
    @Test void otmPairUsesExistingDistinctEvaluatorAndKeepsOrdersOff(){
        var d=daily();var iv=ivStore();
        var row=service(d,iv,true).evaluate(NOW);
        assertEquals("strategy.STR-195",row.strategyId());
        assertEquals("RESEARCH_CANDIDATE",row.status());
        assertEquals("SHORT_STRANGLE_UNBOUNDED_RISK_RESEARCH_ONLY",row.reasonCode());
        assertEquals(2,row.contractsResolved());
        assertEquals(3,row.freshQuotes());
        assertEquals(35,row.historySessions());
        assertEquals("SHORT_STRANGLE_UNBOUNDED_TAIL_RESEARCH_NO_ORDER",row.inputState());
        verify(d).verifiedRecentCloses(NOW);
        verify(iv).oneCurrent(ACCOUNT,CE,PE,NOW);
        assertFalse(FnoShortStrangleV1.evaluate(null,null,List.of(),null,null,null,
            new FnoShortStrangleV1.Rights(false,false,false,false),null,NOW).executable());
        assertFalse(FnoShortStrangleV1.evaluate(null,null,List.of(),null,null,null,
            new FnoShortStrangleV1.Rights(false,false,false,false),null,NOW).orderPlaced());
    }
    @Test void defaultRightsDeniedDoNotTouchMarketHistoryOrQuotes(){
        var d=mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        var iv=mock(FnoVerifiedNiftyShortStrangleSourceStore.class);
        var row=service(d,iv,false).evaluate(NOW);
        assertEquals("BLOCKED_RIGHTS",row.status());
        assertEquals("SHORT_STRANGLE_SOURCE_OR_IV_RIGHTS_UNVERIFIED",row.reasonCode());
        verifyNoInteractions(d,iv);
    }
    @Test void missingOriginalPerLegIvCannotTurnIntoSynthesizedQuote(){
        var iv=ivStore();
        when(iv.oneCurrent(ACCOUNT,CE,PE,NOW)).thenReturn(Optional.empty());
        var row=service(daily(),iv,true).evaluate(NOW);
        assertEquals("BLOCKED_DATA",row.status());
        assertEquals("SHORT_STRANGLE_TWO_VERIFIED_IV_QUOTES_MISSING",row.reasonCode());
    }
    @Test void missingAndNonpositivePutIvNeverProduceResearchCandidate(){
        var original=proof();
        var bad=new Leg(PE,d("1.95"),d("2.00"),d("0"),4,1000,
            original.put().exchangeAt(),original.put().firstKnownAt());
        var iv=ivStore();
        when(iv.oneCurrent(ACCOUNT,CE,PE,NOW)).thenReturn(Optional.of(new Proof(
            original.provider(),original.accountScope(),original.callStrike(),original.putStrike(),original.tick(),original.listedFrom(),
            original.expiry(),original.lotSize(),original.index(),original.call(),
            bad,original.budget(),original.firstKnownAt())));
        var row=service(daily(),iv,true).evaluate(NOW);
        assertEquals("BLOCKED_DATA",row.status());
        assertEquals("SHORT_STRANGLE_IV_CONTRACT_OR_TIMESTAMPS_INVALID",row.reasonCode());
    }
    @Test void staleQuotesAndWrongProviderFailClosed(){
        var p=proof();
        var stale=new Leg(PE,p.put().bid(),p.put().ask(),p.put().ivPercent(),
            4,1000,NOW.minusSeconds(400),NOW.minusSeconds(390));
        var iv=ivStore();
        when(iv.oneCurrent(ACCOUNT,CE,PE,NOW)).thenReturn(Optional.of(new Proof(
            SOURCE,p.accountScope(),p.callStrike(),p.putStrike(),p.tick(),p.listedFrom(),p.expiry(),p.lotSize(),
            p.index(),p.call(),stale,p.budget(),p.firstKnownAt())));
        assertEquals("SHORT_STRANGLE_IV_CONTRACT_OR_TIMESTAMPS_INVALID",
            service(daily(),iv,true).evaluate(NOW).reasonCode());
        when(iv.oneCurrent(ACCOUNT,CE,PE,NOW)).thenReturn(Optional.of(new Proof(
            "ANGEL_ONE_SMARTAPI",p.accountScope(),p.callStrike(),p.putStrike(),p.tick(),p.listedFrom(),p.expiry(),
            p.lotSize(),p.index(),p.call(),p.put(),p.budget(),p.firstKnownAt())));
        assertEquals("SHORT_STRANGLE_IV_CONTRACT_OR_TIMESTAMPS_INVALID",
            service(daily(),iv,true).evaluate(NOW).reasonCode());
    }
    @Test void invalidHistoryAndExceptionsCannotExposeProviderSecrets(){
        var d=daily();var iv=ivStore();
        when(d.verifiedRecentCloses(NOW)).thenReturn(history().subList(0,8));
        var row=service(d,iv,true).evaluate(NOW);
        assertEquals("SHORT_STRANGLE_35_ORIGINAL_DAILY_CLOSES_MISSING",row.reasonCode());
        when(d.verifiedRecentCloses(NOW)).thenReturn(history());
        when(iv.oneCurrent(ACCOUNT,CE,PE,NOW)).thenThrow(new IllegalStateException("secret API key"));
        var failure=service(d,iv,true).evaluate(NOW);
        assertEquals("SHORT_STRANGLE_ORIGINAL_IV_SOURCE_READ_FAILED",failure.reasonCode());
        assertFalse(failure.toString().contains("secret"));
    }

    @Test void brokerMarginEvidenceIsNotATheoreticalLossCap(){
        var base=proof();
        var bad=new Budget(0,500,1,1,1000,80,d("15"),d("10000"),5000,10000,
            d("0"),d("150"));
        var store=ivStore();
        when(store.oneCurrent(ACCOUNT,CE,PE,NOW)).thenReturn(Optional.of(new Proof(
            base.provider(),base.accountScope(),base.callStrike(),base.putStrike(),base.tick(),base.listedFrom(),
            base.expiry(),base.lotSize(),base.index(),base.call(),base.put(),bad,base.firstKnownAt())));
        assertEquals("SHORT_STRANGLE_IV_CONTRACT_OR_TIMESTAMPS_INVALID",
            service(daily(),store,true).evaluate(NOW).reasonCode());
        assertTrue(FnoShortStrangleV1.evaluate(null,null,List.of(),null,null,null,
            new FnoShortStrangleV1.Rights(false,false,false,false),null,NOW)
            .unlimitedTheoreticalUpsideLoss());
    }
    @Test void insufficientRealBidDepthCannotCountAsOptionWritingEvidence(){
        var base=proof();
        var thin=new Leg(CE,d("1.95"),d("2.00"),d("5"),0,1000,
            base.call().exchangeAt(),base.call().firstKnownAt());
        var store=ivStore();
        when(store.oneCurrent(ACCOUNT,CE,PE,NOW)).thenReturn(Optional.of(new Proof(
            base.provider(),base.accountScope(),base.callStrike(),base.putStrike(),base.tick(),base.listedFrom(),
            base.expiry(),base.lotSize(),base.index(),thin,base.put(),
            base.budget(),base.firstKnownAt())));
        var result=service(daily(),store,true).evaluate(NOW);
        assertEquals("BLOCKED_DATA",result.status());
        assertEquals("SHORT_STRANGLE_QUOTES_OR_IV_INVALID",result.reasonCode());
    }
    @Test void wrongBrokerAccountCannotReuseAttestedMarginEvidence(){
        var base=proof();
        var store=ivStore();
        when(store.oneCurrent(ACCOUNT,CE,PE,NOW)).thenReturn(Optional.of(new Proof(
            base.provider(),"other_broker_margin",base.callStrike(),base.putStrike(),base.tick(),
            base.listedFrom(),base.expiry(),base.lotSize(),base.index(),
            base.call(),base.put(),base.budget(),base.firstKnownAt())));
        assertEquals("SHORT_STRANGLE_IV_CONTRACT_OR_TIMESTAMPS_INVALID",
            service(daily(),store,true).evaluate(NOW).reasonCode());
    }

    @Test void sameStrikeProofMustNeverBeAcceptedAsOtmStrangle(){
        var base=proof();
        var store=ivStore();
        when(store.oneCurrent(ACCOUNT,CE,PE,NOW)).thenReturn(Optional.of(new Proof(
            base.provider(),base.accountScope(),d("100"),d("100"),base.tick(),
            base.listedFrom(),base.expiry(),base.lotSize(),base.index(),
            base.call(),base.put(),base.budget(),base.firstKnownAt())));
        assertEquals("SHORT_STRANGLE_IV_CONTRACT_OR_TIMESTAMPS_INVALID",
            service(daily(),store,true).evaluate(NOW).reasonCode());
    }
    @Test void neitherStrikeMayTouchObservedNiftySpot(){
        var base=proof();var store=ivStore();
        for(var strike:List.of("100","95")){
            var row=new Proof(base.provider(),base.accountScope(),d(strike),
                base.putStrike(),base.tick(),base.listedFrom(),base.expiry(),
                base.lotSize(),base.index(),base.call(),base.put(),
                base.budget(),base.firstKnownAt());
            when(store.oneCurrent(ACCOUNT,CE,PE,NOW)).thenReturn(Optional.of(row));
            assertEquals("SHORT_STRANGLE_IV_CONTRACT_OR_TIMESTAMPS_INVALID",
                service(daily(),store,true).evaluate(NOW).reasonCode());
        }
    }
}
