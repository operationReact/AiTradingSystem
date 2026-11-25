package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedEquityOwnedOptionStore;
import in.aitradingsystem.marketdata.FnoVerifiedOwnedEquityCollarCostStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FnoEquityCollarResearchServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final String ACCOUNT="private_owned_account";
    private static final String PROVIDER="UPSTOX_ANALYTICS";
    private static final String STOCK="NSE_EQ|EXAMPLECO";
    private static final String PE="NSE_FO|EXAMPLECO_20261127_95_PE";
    private static final String CE="NSE_FO|EXAMPLECO_20261127_110_CE";
    private static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    private static final BigDecimal PRICE=new BigDecimal("94");

    private static MarketSessionCalendar calendar(){
        var c=mock(MarketSessionCalendar.class);
        when(c.isInsideCashSession(NOW)).thenReturn(true);
        when(c.isTradingDay(any(LocalDate.class))).thenAnswer(inv->{
            LocalDate d=inv.getArgument(0);
            return d.getDayOfWeek().getValue()<=5;
        });
        return c;
    }
    private static List<FnoVerifiedEquityOwnedOptionStore.VerifiedClose> history(){
        var dates=new ArrayList<LocalDate>();
        var date=LocalDate.of(2026,11,19);
        while(dates.size()<35){
            if(date.getDayOfWeek().getValue()<=5)dates.add(0,date);
            date=date.minusDays(1);
        }
        var rows=new ArrayList<FnoVerifiedEquityOwnedOptionStore.VerifiedClose>();
        for(int i=0;i<35;i++){
            Instant close=dates.get(i).atTime(15,30)
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            rows.add(new FnoVerifiedEquityOwnedOptionStore.VerifiedClose(
                PROVIDER,STOCK,dates.get(i),
                i==34?PRICE:new BigDecimal("100"),close.plusSeconds(60),close));
        }
        return rows;
    }
    private static FnoVerifiedEquityOwnedOptionStore.Option option(boolean put){
        var holding=new FnoVerifiedEquityOwnedOptionStore.Holding(
            PROVIDER,ACCOUNT,STOCK,50,new BigDecimal("80"),NOW.minusSeconds(45));
        return new FnoVerifiedEquityOwnedOptionStore.Option(
            PROVIDER,ACCOUNT,STOCK,put?PE:CE,put?"PE":"CE",
            LocalDate.of(2026,11,1),EXPIRY,50,
            new BigDecimal(put?"95":"110"),new BigDecimal("0.05"),holding,
            PRICE,NOW.minusSeconds(35),NOW.minusSeconds(25),
            new BigDecimal(put?"2.00":"2.40"),
            new BigDecimal(put?"2.05":"2.45"),
            20,20,1200,NOW.minusSeconds(35),NOW.minusSeconds(25),
            2000,300,50,300,2,50,2000,
            new BigDecimal("10"),new BigDecimal("10000"),10000,50,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }
    private static FnoVerifiedEquityOwnedOptionStore stock(){
        var s=mock(FnoVerifiedEquityOwnedOptionStore.class);
        when(s.readCloses(STOCK,NOW)).thenReturn(history());
        when(s.oneSettledLot(ACCOUNT,STOCK,PE,"PE",NOW))
            .thenReturn(Optional.of(option(true)));
        when(s.oneSettledLot(ACCOUNT,STOCK,CE,"CE",NOW))
            .thenReturn(Optional.of(option(false)));
        return s;
    }
    private static FnoVerifiedOwnedEquityCollarCostStore.CostProof cost(){
        var assumptions=new FnoEquityCollarV1.Assumptions(
            2000,300,50,50,300,2,50,2000,
            new BigDecimal("15"),new BigDecimal("10000"),10000,3000);
        return new FnoVerifiedOwnedEquityCollarCostStore.CostProof(
            PROVIDER,ACCOUNT,STOCK,PE,CE,EXPIRY,50,NOW.minusSeconds(25),
            new BigDecimal("93.99"),new BigDecimal("94.01"),
            NOW.minusSeconds(35),NOW.minusSeconds(25),assumptions);
    }
    private static FnoEquityCollarResearchService service(
        FnoVerifiedEquityOwnedOptionStore s,FnoVerifiedOwnedEquityCollarCostStore costs,
        boolean rights,String account){
        return new FnoEquityCollarResearchService(
            s,costs,calendar(),rights,rights,rights,rights,rights,rights,
            account,STOCK,PE,CE);
    }
    @Test void fullSettledSharesAndThreeOriginalTimeQuotesAndIndependentFeesAreResearchOnly(){
        var s=stock();
        var costs=mock(FnoVerifiedOwnedEquityCollarCostStore.class);
        when(costs.oneCurrent(ACCOUNT,STOCK,PE,CE,EXPIRY,50,NOW))
            .thenReturn(Optional.of(cost()));
        var row=service(s,costs,true,ACCOUNT).evaluate(NOW);
        assertEquals("strategy.STR-189",row.strategyId());
        assertEquals("RESEARCH_CANDIDATE",row.status());
        assertEquals("COLLAR_RESEARCH_ONLY",row.reasonCode());
        assertEquals(35,row.historySessions());
        assertEquals(2,row.contractsResolved());
        assertEquals(3,row.freshQuotes());
        assertEquals("BROKER_SETTLED_EQUITY_COLLAR_SOURCE_RESEARCH_NO_ORDER",row.inputState());
        verify(s).oneSettledLot(ACCOUNT,STOCK,PE,"PE",NOW);
        verify(s).oneSettledLot(ACCOUNT,STOCK,CE,"CE",NOW);
        verify(costs).oneCurrent(ACCOUNT,STOCK,PE,CE,EXPIRY,50,NOW);
    }
    @Test void noSourceRightsMeansNoBrokerAccess(){
        var s=mock(FnoVerifiedEquityOwnedOptionStore.class);
        var c=mock(FnoVerifiedOwnedEquityCollarCostStore.class);
        var row=service(s,c,false,ACCOUNT).evaluate(NOW);
        assertEquals("BLOCKED_RIGHTS",row.status());
        assertEquals("COLLAR_SOURCE_RIGHTS_UNVERIFIED",row.reasonCode());
        verifyNoInteractions(s,c);
    }
    @Test void noBrokerAccountOrNoSettledSharesAreBlocked(){
        var s=stock();var c=mock(FnoVerifiedOwnedEquityCollarCostStore.class);
        assertEquals("COLLAR_ACCOUNT_OR_LEGS_NOT_CONFIGURED",
            service(s,c,true,"").evaluate(NOW).reasonCode());
        when(s.oneSettledLot(ACCOUNT,STOCK,CE,"CE",NOW)).thenReturn(Optional.empty());
        var row=service(s,c,true,ACCOUNT).evaluate(NOW);
        assertEquals("BLOCKED_DATA",row.status());
        assertEquals("COLLAR_BOTH_OPTIONS_AND_SETTLED_SHARES_MISSING",row.reasonCode());
        verifyNoInteractions(c);
    }
    @Test void missingOrInconsistentCombinedTaxAndFeeReceiptFailsClosed(){
        var s=stock();var c=mock(FnoVerifiedOwnedEquityCollarCostStore.class);
        assertEquals("COLLAR_VERIFIED_UNDUPLICATED_COST_PROOF_MISSING",
            service(s,c,true,ACCOUNT).evaluate(NOW).reasonCode());
        var old=cost();
        when(c.oneCurrent(ACCOUNT,STOCK,PE,CE,EXPIRY,50,NOW)).thenReturn(
            Optional.of(new FnoVerifiedOwnedEquityCollarCostStore.CostProof(
                old.provider(),old.accountScope(),old.stockKey(),old.putKey(),
                old.callKey(),old.expiry(),old.lotSize(),old.firstKnownAt(),
                new BigDecimal("95"),new BigDecimal("95.01"),
                old.equityExchangeAt(),old.equityFirstKnownAt(),old.assumptions())));
        assertEquals("COLLAR_EQUITY_QUOTE_SOURCE_MISMATCH",
            service(s,c,true,ACCOUNT).evaluate(NOW).reasonCode());
    }
    @Test void staleOrContradictoryOwnershipIsNotReusedForSecondOptionLeg(){
        var s=stock();var c=mock(FnoVerifiedOwnedEquityCollarCostStore.class);
        var original=option(false);
        var altered=new FnoVerifiedEquityOwnedOptionStore.Holding(
            PROVIDER,ACCOUNT,STOCK,49,new BigDecimal("80"),NOW.minusSeconds(45));
        var bad=new FnoVerifiedEquityOwnedOptionStore.Option(
            original.provider(),original.accountScope(),original.equityKey(),
            original.optionKey(),original.kind(),original.listedFrom(),original.expiry(),
            original.lotSize(),original.strike(),original.tickSize(),altered,
            original.equityPrice(),original.equityExchangeAt(),original.equityFirstKnownAt(),
            original.bid(),original.ask(),original.bidDepthLots(),original.askDepthLots(),
            original.oiLots(),original.optionExchangeAt(),original.optionFirstKnownAt(),
            original.expiryMoveBps(),original.ownedTriggerBps(),original.slippageBps(),
            original.maxSpreadBps(),original.minDepthLots(),original.minOiLots(),
            original.maxStrikeDistanceBps(),original.allInFeesInr(),original.capitalInr(),
            original.maxCapitalAllocationBps(),original.minimumBenefitInr(),
            original.masterDigest());
        when(s.oneSettledLot(ACCOUNT,STOCK,CE,"CE",NOW)).thenReturn(Optional.of(bad));
        assertEquals("COLLAR_STOCK_OWNERSHIP_OR_OPTION_PAIR_MISMATCH",
            service(s,c,true,ACCOUNT).evaluate(NOW).reasonCode());
        verifyNoInteractions(c);
    }
    @Test void independentCombinedFeeStoreErrorsAreSanitized(){
        var c=mock(FnoVerifiedOwnedEquityCollarCostStore.class);
        when(c.oneCurrent(ACCOUNT,STOCK,PE,CE,EXPIRY,50,NOW))
            .thenThrow(new IllegalStateException("private broker token"));
        var row=service(stock(),c,true,ACCOUNT).evaluate(NOW);
        assertEquals("COLLAR_INDEPENDENT_COST_SOURCE_UNAVAILABLE",row.reasonCode());
        assertFalse(row.toString().contains("token"));
    }
}
