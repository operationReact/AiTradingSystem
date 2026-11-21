package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedEquityOwnedOptionStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FnoOwnedEquityOptionsResearchServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String ACCOUNT = "private_owned_account";
    private static final String PROVIDER = "UPSTOX_ANALYTICS";
    private static final String STOCK = "NSE_EQ|EXAMPLECO";
    private static final String PE = "NSE_FO|EXAMPLECO_20261127_95_PE";
    private static final String CE = "NSE_FO|EXAMPLECO_20261127_100_CE";

    private static MarketSessionCalendar calendar() {
        var cal = mock(MarketSessionCalendar.class);
        when(cal.isInsideCashSession(NOW)).thenReturn(true);
        when(cal.isTradingDay(any(LocalDate.class))).thenAnswer(i -> {
            LocalDate d = i.getArgument(0);
            return d.getDayOfWeek().getValue() <= 5;
        });
        return cal;
    }

    private static List<FnoVerifiedEquityOwnedOptionStore.VerifiedClose> history() {
        var days = new ArrayList<LocalDate>();
        LocalDate date = LocalDate.of(2026,11,19);
        while (days.size() < 35) {
            if (date.getDayOfWeek().getValue() <= 5) days.add(0,date);
            date = date.minusDays(1);
        }
        var rows = new ArrayList<FnoVerifiedEquityOwnedOptionStore.VerifiedClose>();
        for (int i=0;i<days.size();i++) {
            Instant close = days.get(i).atTime(15,30).atZone(IST).toInstant();
            rows.add(new FnoVerifiedEquityOwnedOptionStore.VerifiedClose(
                PROVIDER,STOCK,days.get(i),
                new BigDecimal(i==34?"94":"100"),
                close.plusSeconds(60),close));
        }
        return rows;
    }

    private static FnoVerifiedEquityOwnedOptionStore.Option proof(boolean put) {
        var holding = new FnoVerifiedEquityOwnedOptionStore.Holding(
            PROVIDER,ACCOUNT,STOCK,50,new BigDecimal("80"),NOW.minusSeconds(45));
        return new FnoVerifiedEquityOwnedOptionStore.Option(
            PROVIDER,ACCOUNT,STOCK,put?PE:CE,put?"PE":"CE",
            LocalDate.of(2026,11,1),LocalDate.of(2026,11,27),50,
            new BigDecimal(put?"95":"100"),new BigDecimal("0.05"),holding,
            new BigDecimal("94"),NOW.minusSeconds(35),NOW.minusSeconds(25),
            new BigDecimal(put?"1.95":"2.50"),
            new BigDecimal(put?"2.00":"2.55"),
            20,20,1200,NOW.minusSeconds(35),NOW.minusSeconds(25),
            put?2000:1500,put?300:500,50,300,2,50,1000,
            new BigDecimal("10"),new BigDecimal("10000"),
            put?3000:10000,put?100:50,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    private static FnoVerifiedEquityOwnedOptionStore source() {
        var s = mock(FnoVerifiedEquityOwnedOptionStore.class);
        when(s.readCloses(STOCK,NOW)).thenReturn(history());
        when(s.oneSettledLot(ACCOUNT,STOCK,PE,"PE",NOW))
            .thenReturn(Optional.of(proof(true)));
        when(s.oneSettledLot(ACCOUNT,STOCK,CE,"CE",NOW))
            .thenReturn(Optional.of(proof(false)));
        return s;
    }

    private static FnoOwnedEquityOptionsResearchService service(
            FnoVerifiedEquityOwnedOptionStore store,
            boolean rights,String account,String stock) {
        return new FnoOwnedEquityOptionsResearchService(
            store,calendar(),rights,rights,rights,rights,rights,
            account,stock,PE,CE);
    }

    private static Map<String,FnoFuturesResearchService.StrategyStatus> report(
            FnoOwnedEquityOptionsResearchService svc) {
        return svc.evaluateAll(NOW).stream().collect(
            Collectors.toMap(FnoFuturesResearchService.StrategyStatus::strategyId,s->s));
    }

    @Test
    void twoDistinctEvaluatorsUseOneSettledHoldingLotAndSourceFirstKnownStockHistory() {
        var s = source();
        var rows = report(service(s,true,ACCOUNT,STOCK));
        assertEquals(2,rows.size());
        var protectedPut = rows.get("strategy.STR-187");
        assertEquals("RESEARCH_CANDIDATE",protectedPut.status());
        assertEquals("PROTECTIVE_PUT_RESEARCH_ONLY",protectedPut.reasonCode());
        assertEquals(35,protectedPut.historySessions());
        assertEquals(1,protectedPut.contractsResolved());
        assertEquals(2,protectedPut.freshQuotes());
        assertEquals("SETTLED_EQUITY_PROTECTIVE_PUT_RESEARCH_NO_ORDER",
            protectedPut.inputState());
        var call = rows.get("strategy.STR-188");
        assertEquals("RESEARCH_CANDIDATE",call.status());
        assertEquals("COVERED_CALL_RESEARCH_ONLY",call.reasonCode());
        assertEquals("SETTLED_EQUITY_COVERED_CALL_RESEARCH_NO_ORDER",
            call.inputState());
        verify(s,times(1)).readCloses(STOCK,NOW);
        verify(s).oneSettledLot(ACCOUNT,STOCK,PE,"PE",NOW);
        verify(s).oneSettledLot(ACCOUNT,STOCK,CE,"CE",NOW);
    }

    @Test
    void rightsDeniedCannotFetchSensitiveBrokerHoldingsOrOptionPremium() {
        var s = mock(FnoVerifiedEquityOwnedOptionStore.class);
        var rows = report(service(s,false,ACCOUNT,STOCK));
        assertEquals("BLOCKED_RIGHTS",rows.get("strategy.STR-187").status());
        assertEquals("BLOCKED_RIGHTS",rows.get("strategy.STR-188").status());
        verifyNoInteractions(s);
    }

    @Test
    void missingAccountAndIndexProxyDoNotInventStockPositions() {
        var s = mock(FnoVerifiedEquityOwnedOptionStore.class);
        var missing = report(service(s,true,"",STOCK));
        assertEquals("OWNED_STOCK_ACCOUNT_OR_EQUITY_NOT_CONFIGURED",
            missing.get("strategy.STR-187").reasonCode());
        var proxy = report(service(s,true,ACCOUNT,"NSE_INDEX|Nifty 50"));
        assertEquals("BLOCKED_DATA",proxy.get("strategy.STR-188").status());
        assertEquals("OWNED_STOCK_ACCOUNT_OR_EQUITY_NOT_CONFIGURED",
            proxy.get("strategy.STR-188").reasonCode());
        verifyNoInteractions(s);
    }

    @Test
    void noAuthenticatedSettledSharesBlocksRatherThanConstructsNakedCall() {
        var s = mock(FnoVerifiedEquityOwnedOptionStore.class);
        when(s.readCloses(STOCK,NOW)).thenReturn(history());
        var rows = report(service(s,true,ACCOUNT,STOCK));
        assertEquals("BLOCKED_DATA",rows.get("strategy.STR-187").status());
        assertEquals("BLOCKED_DATA",rows.get("strategy.STR-188").status());
        assertEquals("OWNED_STOCK_BROKER_SETTLED_LOT_PROOF_MISSING",
            rows.get("strategy.STR-188").reasonCode());
        assertEquals(0,rows.get("strategy.STR-188").contractsResolved());
    }

    @Test
    void mismatchedSettledShareCountAndOptionLotCannotProtectPaperShares() {
        var s = source();
        var old = proof(false);
        var holding = new FnoVerifiedEquityOwnedOptionStore.Holding(
            PROVIDER,ACCOUNT,STOCK,1,new BigDecimal("80"),NOW.minusSeconds(45));
        var bad = new FnoVerifiedEquityOwnedOptionStore.Option(
            old.provider(),old.accountScope(),old.equityKey(),old.optionKey(),
            old.kind(),old.listedFrom(),old.expiry(),old.lotSize(),
            old.strike(),old.tickSize(),holding,old.equityPrice(),
            old.equityExchangeAt(),old.equityFirstKnownAt(),
            old.bid(),old.ask(),old.bidDepthLots(),old.askDepthLots(),old.oiLots(),
            old.optionExchangeAt(),old.optionFirstKnownAt(),
            old.expiryMoveBps(),old.ownedTriggerBps(),old.slippageBps(),
            old.maxSpreadBps(),old.minDepthLots(),old.minOiLots(),
            old.maxStrikeDistanceBps(),old.allInFeesInr(),old.capitalInr(),
            old.maxCapitalAllocationBps(),old.minimumBenefitInr(),old.masterDigest());
        when(s.oneSettledLot(ACCOUNT,STOCK,CE,"CE",NOW)).thenReturn(Optional.of(bad));
        var row = report(service(s,true,ACCOUNT,STOCK)).get("strategy.STR-188");
        assertEquals("BLOCKED_DATA",row.status());
        assertEquals("OWNED_STOCK_SOURCE_CONTRACT_OR_ACCOUNT_MISMATCH",
            row.reasonCode());
        assertEquals(0,row.contractsResolved());
    }

    @Test
    void staleOrMissingEquityHistoryFailsClosedBeforeBrokerRead() {
        var s = source();
        when(s.readCloses(STOCK,NOW)).thenReturn(history().subList(0,34));
        var rows = report(service(s,true,ACCOUNT,STOCK));
        assertEquals("OWNED_STOCK_35_VERIFIED_CLOSES_MISSING",
            rows.get("strategy.STR-187").reasonCode());
        verify(s,never()).oneSettledLot(any(),any(),any(),any(),any());
    }

    @Test
    void sourceFailureCannotLeakPrivateAccountOrReportOrderReadiness() {
        var s = source();
        when(s.oneSettledLot(ACCOUNT,STOCK,CE,"CE",NOW))
            .thenThrow(new IllegalStateException("private settled shares and broker token"));
        var rows = report(service(s,true,ACCOUNT,STOCK));
        assertEquals("RESEARCH_CANDIDATE",rows.get("strategy.STR-187").status());
        var call = rows.get("strategy.STR-188");
        assertEquals("BLOCKED_DATA",call.status());
        assertEquals("OWNED_STOCK_HOLDINGS_OR_OPTION_READ_UNAVAILABLE",call.reasonCode());
        assertFalse(call.toString().contains("private"));
    }
}
