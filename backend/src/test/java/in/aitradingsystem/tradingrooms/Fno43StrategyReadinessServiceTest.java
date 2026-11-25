package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class Fno43StrategyReadinessServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private static Fno43StrategyReadinessService create(FnoFuturesResearchService futures) {
        return new Fno43StrategyReadinessService(futures, new ObjectMapper());
    }

    @Test
    void exact43ManifestDefinitionsExposeDistinctInputContractsAndNeverPromoteReadiness() {
        var futures = mock(FnoFuturesResearchService.class);
        when(futures.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-170", "BLOCKED_DATA",
                "SPOT_NOT_VERIFIED", NOW, "NONE", 1, 0, 0, "NO_SPOT"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-171", "BLOCKED_RIGHTS",
                "SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 2, 0, 0, "PAIR_RESOLVED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-172", "BLOCKED_DATA",
                "HISTORY_INSUFFICIENT", NOW, "NONE", 2, 0, 1, "PAIR_RESOLVED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-173", "BLOCKED_RIGHTS",
                "OI_SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "OI_INPUTS_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-174", "BLOCKED_RIGHTS",
                "PRICE_OI_SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "PRICE_OI_FINAL_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-175", "BLOCKED_RIGHTS",
                "CASH_FUT_SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "CASH_FUT_FEASIBILITY_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-176", "BLOCKED_RIGHTS",
                "FUT_MOM_SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "FUT_MOM_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-177", "BLOCKED_RIGHTS",
                "FUT_MR_SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "FUT_MR_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-178", "BLOCKED_RIGHTS",
                "HEDGE_RESEARCH_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "INDEX_HEDGE_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-179", "BLOCKED_RIGHTS",
                "LIQUIDITY_SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "LIQUIDITY_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-180", "BLOCKED_RIGHTS",
                "EXPIRY_BASIS_SOURCE_RIGHTS_MISSING", NOW, "NONE", 0, 0, 0, "EXPIRY_BASIS_VERIFIED_SOURCE_BLOCKED")));
        var options = mock(FnoOptionsResearchService.class);
        when(options.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-181", "BLOCKED_RIGHTS",
                "LONG_CALL_SOURCE_RIGHTS_UNVERIFIED", NOW, "NONE", 0, 0, 0, "NIFTY_OPTION_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-182", "BLOCKED_RIGHTS",
                "LONG_PUT_SOURCE_RIGHTS_UNVERIFIED", NOW, "NONE", 0, 0, 0, "NIFTY_OPTION_SOURCE_BLOCKED")));
        var spreads = mock(FnoDebitSpreadResearchService.class);
        when(spreads.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-183", "BLOCKED_RIGHTS",
                "DEBIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED", NOW, "NONE", 0, 0, 0, "DEBIT_SPREAD_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-184", "BLOCKED_RIGHTS",
                "DEBIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED", NOW, "NONE", 0, 0, 0, "DEBIT_SPREAD_SOURCE_BLOCKED")));
        var credits = mock(FnoCreditSpreadResearchService.class);
        when(credits.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-185", "BLOCKED_RIGHTS",
                "CREDIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED", NOW, "NONE", 0, 0, 0, "CREDIT_SPREAD_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-186", "BLOCKED_RIGHTS",
                "CREDIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED", NOW, "NONE", 0, 0, 0, "CREDIT_SPREAD_SOURCE_BLOCKED")));
        var owned = mock(FnoOwnedEquityOptionsResearchService.class);
        when(owned.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-187", "BLOCKED_RIGHTS",
                "OWNED_STOCK_SOURCE_RIGHTS_UNVERIFIED", NOW, "NONE", 0, 0, 0, "OWNED_EQUITY_OPTION_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-188", "BLOCKED_RIGHTS",
                "OWNED_STOCK_SOURCE_RIGHTS_UNVERIFIED", NOW, "NONE", 0, 0, 0, "OWNED_EQUITY_OPTION_SOURCE_BLOCKED")));
        var collar = mock(FnoEquityCollarResearchService.class);
        when(collar.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-189", "BLOCKED_RIGHTS",
                "COLLAR_SOURCE_RIGHTS_UNVERIFIED", NOW, "NONE", 0, 0, 0, "COLLAR_SOURCE_BLOCKED"));
        var ratio = mock(FnoRatioSpreadResearchService.class);
        when(ratio.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-190",
                "BLOCKED_RIGHTS","RATIO_SOURCE_RIGHTS_UNVERIFIED",NOW,"NONE",
                0,0,0,"RATIO_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-191",
                "BLOCKED_RIGHTS","RATIO_SOURCE_RIGHTS_UNVERIFIED",NOW,"NONE",
                0,0,0,"RATIO_SOURCE_BLOCKED")));
        var straddle = mock(FnoStraddleResearchService.class);
        when(straddle.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-192",
                "BLOCKED_RIGHTS","STRADDLE_SOURCE_OR_IV_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"STRADDLE_SOURCE_BLOCKED_NO_ORDER"));
        var strangle = mock(FnoStrangleResearchService.class);
        when(strangle.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-193",
                "BLOCKED_RIGHTS","STRANGLE_SOURCE_OR_IV_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"STRANGLE_SOURCE_BLOCKED_NO_ORDER"));
        var shortStraddle=mock(FnoShortStraddleResearchService.class);
        when(shortStraddle.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-194",
                "BLOCKED_RIGHTS","SHORT_STRADDLE_SOURCE_OR_IV_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"SHORT_STRADDLE_SOURCE_BLOCKED_NO_ORDER"));
        var shortStrangle=mock(FnoShortStrangleResearchService.class);
        when(shortStrangle.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-195",
                "BLOCKED_RIGHTS","SHORT_STRANGLE_SOURCE_OR_IV_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"SHORT_STRANGLE_SOURCE_BLOCKED_NO_ORDER"));
        var ironCondor=mock(FnoIronCondorResearchService.class);
        when(ironCondor.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-196",
                "BLOCKED_RIGHTS","IRON_CONDOR_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"IRON_CONDOR_SOURCE_BLOCKED_NO_ORDER"));
        var ironButterfly=mock(FnoIronButterflyResearchService.class);
        when(ironButterfly.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-197",
                "BLOCKED_RIGHTS","IRON_BUTTERFLY_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"IRON_BUTTERFLY_SOURCE_BLOCKED_NO_ORDER"));
        var longButterfly=mock(FnoLongButterflyResearchService.class);
        when(longButterfly.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-198",
                "BLOCKED_RIGHTS","LONG_BUTTERFLY_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"LONG_BUTTERFLY_SOURCE_BLOCKED_NO_ORDER"));
        var calendarSpread=mock(FnoCalendarSpreadResearchService.class);
        when(calendarSpread.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-199",
                "BLOCKED_RIGHTS","CALENDAR_ORIGINAL_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"CALENDAR_UNRESOLVED_FAR_MARK_SOURCE_BLOCKED_NO_ORDER"));
        var diagonalSpread=mock(FnoDiagonalSpreadResearchService.class);
        when(diagonalSpread.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-200",
                "BLOCKED_RIGHTS","DIAGONAL_ORIGINAL_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"DIAGONAL_UNRESOLVED_FAR_MARK_SOURCE_BLOCKED_NO_ORDER"));
        var ivRank=mock(FnoIvRankResearchService.class);
        when(ivRank.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-201",
                "BLOCKED_RIGHTS","IV_RANK_ORIGINAL_HISTORY_OR_IV_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"IV_RANK_SOURCE_BLOCKED_NO_ORDER"));
        var volatilityCrush=mock(FnoVolatilityCrushResearchService.class);
        when(volatilityCrush.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-202",
                "BLOCKED_RIGHTS","VOL_CRUSH_ORIGINAL_EVENT_AND_IV_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"VOL_CRUSH_RETROSPECTIVE_SOURCE_BLOCKED_NO_ORDER"));
        var extra0=mock(FnoGammaScalpingResearchService.class);
        when(extra0.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-203",
                "BLOCKED_RIGHTS","STR_203_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"STR_203_ORIGINAL_SOURCE_BLOCKED_NO_ORDER"));
        var extra1=mock(FnoDeltaHedgeResearchService.class);
        when(extra1.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-204",
                "BLOCKED_RIGHTS","STR_204_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"STR_204_ORIGINAL_SOURCE_BLOCKED_NO_ORDER"));
        var extra2=mock(FnoBetaHedgeOptionsResearchService.class);
        when(extra2.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-205",
                "BLOCKED_RIGHTS","STR_205_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"STR_205_ORIGINAL_SOURCE_BLOCKED_NO_ORDER"));
        var extra3=mock(FnoTailRiskPutHedgeResearchService.class);
        when(extra3.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-206",
                "BLOCKED_RIGHTS","STR_206_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"STR_206_ORIGINAL_SOURCE_BLOCKED_NO_ORDER"));
        var final0=mock(FnoPutSpreadCollarResearchService.class);
        when(final0.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-207",
                "BLOCKED_RIGHTS","STR_207_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"STR_207_SOURCE_DENIED_NO_ORDER"));
        var final1=mock(FnoDefinedRiskSpreadRollResearchService.class);
        when(final1.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-208",
                "BLOCKED_RIGHTS","STR_208_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"STR_208_SOURCE_DENIED_NO_ORDER"));
        var final2=mock(FnoExpiryRiskNeutralizationResearchService.class);
        when(final2.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-209",
                "BLOCKED_RIGHTS","STR_209_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"STR_209_SOURCE_DENIED_NO_ORDER"));
        var final3=mock(FnoPortfolioProtectiveOverlayResearchService.class);
        when(final3.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-210",
                "BLOCKED_RIGHTS","STR_210_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"STR_210_SOURCE_DENIED_NO_ORDER"));
        var final4=mock(FnoGreeksExposureRebalanceResearchService.class);
        when(final4.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-211",
                "BLOCKED_RIGHTS","STR_211_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"STR_211_SOURCE_DENIED_NO_ORDER"));
        var final5=mock(FnoSkewBasedHedgeResearchService.class);
        when(final5.evaluate(NOW)).thenReturn(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-212",
                "BLOCKED_RIGHTS","STR_212_SOURCE_RIGHTS_UNVERIFIED",NOW,
                "NONE",0,0,0,"STR_212_SOURCE_DENIED_NO_ORDER"));
        var report = new Fno43StrategyReadinessService(
            futures, options, spreads, credits, owned, collar, ratio,
            straddle, strangle, shortStraddle, shortStrangle, ironCondor,
            ironButterfly, longButterfly, calendarSpread, diagonalSpread,
            ivRank, volatilityCrush, extra0, extra1, extra2, extra3,
            final0,final1,final2,final3,final4,final5,new ObjectMapper()).inspect(NOW);

        assertEquals(43, report.manifestStrategies());
        assertEquals(43, report.strategies().size());
        assertEquals(43, report.distinctEvaluatorsObserved());
        assertEquals(0, report.signalRoutesWithoutDistinctEvaluator());
        assertEquals(0, report.strategiesWithoutDirectEvaluatorAdapter());
        assertEquals(0, report.independentlyVerifiedSourceStrategies());
        assertEquals(0, report.historicalDataE2EValidated());
        assertEquals(0, report.paperExecutionValidated());
        assertEquals(0, report.liveExecutionValidated());
        assertEquals(43, report.strategies().stream().map(r -> r.strategyId()).distinct().count());
        assertEquals("strategy.STR-170", report.strategies().get(0).strategyId());
        assertEquals("strategy.STR-212", report.strategies().get(42).strategyId());
        assertTrue(report.strategies().stream().allMatch(r -> !r.requiredInputs().isEmpty()
            && r.evaluatorSource().endsWith(".java")
            && !r.independentlyVerifiedSourceRights()
            && !r.historicalDataE2EValidated()
            && !r.paperExecutionValidated() && !r.liveExecutionValidated()));
        assertEquals(43, report.strategies().stream()
            .filter(Fno43StrategyReadinessService.StrategyRow::distinctEvaluatorObserved).count());
        var byId = report.strategies().stream().collect(
            Collectors.toMap(r -> r.strategyId(), r -> r));
        assertEquals("SPOT_NOT_VERIFIED", byId.get("strategy.STR-170").reasonCode());
        assertEquals(1, byId.get("strategy.STR-170").contractsResolved());
        assertEquals("DIRECT_EVALUATOR_OBSERVED", byId.get("strategy.STR-181").integrationState());
        assertTrue(byId.get("strategy.STR-181").distinctEvaluatorObserved());
        assertEquals("LONG_PUT_SOURCE_RIGHTS_UNVERIFIED", byId.get("strategy.STR-182").reasonCode());
        assertEquals("DIRECT_EVALUATOR_OBSERVED", byId.get("strategy.STR-212").integrationState());
        assertEquals(Set.of("strategy.STR-170", "strategy.STR-171", "strategy.STR-172", "strategy.STR-173", "strategy.STR-174", "strategy.STR-175", "strategy.STR-176", "strategy.STR-177", "strategy.STR-178", "strategy.STR-179", "strategy.STR-180", "strategy.STR-181", "strategy.STR-182", "strategy.STR-183", "strategy.STR-184", "strategy.STR-185", "strategy.STR-186", "strategy.STR-187", "strategy.STR-188", "strategy.STR-189", "strategy.STR-190", "strategy.STR-191", "strategy.STR-192", "strategy.STR-193", "strategy.STR-194", "strategy.STR-195", "strategy.STR-196", "strategy.STR-197", "strategy.STR-198", "strategy.STR-199", "strategy.STR-200", "strategy.STR-201", "strategy.STR-202", "strategy.STR-203", "strategy.STR-204", "strategy.STR-205", "strategy.STR-206", "strategy.STR-207", "strategy.STR-208", "strategy.STR-209", "strategy.STR-210", "strategy.STR-211", "strategy.STR-212"),
            report.strategies().stream().filter(
                Fno43StrategyReadinessService.StrategyRow::distinctEvaluatorObserved)
                .map(Fno43StrategyReadinessService.StrategyRow::strategyId)
                .collect(Collectors.toSet()));
        verify(futures, times(1)).evaluateAll(NOW);
        verifyNoMoreInteractions(futures);
        verify(options, times(1)).evaluateAll(NOW);
        verifyNoMoreInteractions(options);
        verify(spreads, times(1)).evaluateAll(NOW);
        verifyNoMoreInteractions(spreads);
        verify(credits, times(1)).evaluateAll(NOW);
        verifyNoMoreInteractions(credits);
        verify(owned, times(1)).evaluateAll(NOW);
        verifyNoMoreInteractions(owned);
        verify(straddle).evaluate(NOW);
        verifyNoMoreInteractions(straddle);
        verify(strangle).evaluate(NOW);
        verifyNoMoreInteractions(strangle);
        verify(shortStraddle).evaluate(NOW);
        verifyNoMoreInteractions(shortStraddle);
        verify(shortStrangle).evaluate(NOW);
        verifyNoMoreInteractions(shortStrangle);
        verify(ironCondor).evaluate(NOW);
        verifyNoMoreInteractions(ironCondor);
        verify(ironButterfly).evaluate(NOW);
        verifyNoMoreInteractions(ironButterfly);
        verify(longButterfly).evaluate(NOW);
        verifyNoMoreInteractions(longButterfly);
        verify(calendarSpread).evaluate(NOW);
        verifyNoMoreInteractions(calendarSpread);
        verify(diagonalSpread).evaluate(NOW);
        verifyNoMoreInteractions(diagonalSpread);
        verify(ivRank).evaluate(NOW);
        verifyNoMoreInteractions(ivRank);
        verify(volatilityCrush).evaluate(NOW);
        verifyNoMoreInteractions(volatilityCrush);
        verify(extra0).evaluate(NOW);
        verifyNoMoreInteractions(extra0);
        verify(extra1).evaluate(NOW);
        verifyNoMoreInteractions(extra1);
        verify(extra2).evaluate(NOW);
        verifyNoMoreInteractions(extra2);
        verify(extra3).evaluate(NOW);
        verifyNoMoreInteractions(extra3);
        verify(final0).evaluate(NOW);
        verifyNoMoreInteractions(final0);
        verify(final1).evaluate(NOW);
        verifyNoMoreInteractions(final1);
        verify(final2).evaluate(NOW);
        verifyNoMoreInteractions(final2);
        verify(final3).evaluate(NOW);
        verifyNoMoreInteractions(final3);
        verify(final4).evaluate(NOW);
        verifyNoMoreInteractions(final4);
        verify(final5).evaluate(NOW);
        verifyNoMoreInteractions(final5);
    }

    @Test
    void providerFailureDoesNotInventEvidenceOrLeakExceptionDetails() {
        var futures = mock(FnoFuturesResearchService.class);
        when(futures.evaluateAll(NOW)).thenThrow(new IllegalStateException("secret provider response"));
        var report = create(futures).inspect(NOW);
        assertEquals(43, report.strategies().size());
        assertEquals(0, report.distinctEvaluatorsObserved());
        assertEquals(43, report.strategies().stream()
            .filter(r -> "DIRECT_ADAPTER_UNAVAILABLE".equals(r.integrationState())).count());
        assertTrue(report.strategies().stream().noneMatch(r ->
            r.reasonCode().contains("secret") || r.distinctEvaluatorObserved()));
    }

    @Test
    void duplicateRuntimeEvidenceFailsClosed() {
        var futures = mock(FnoFuturesResearchService.class);
        var row = new FnoFuturesResearchService.StrategyStatus("strategy.STR-170",
            "RESEARCH_CANDIDATE", "MODEL_CANDIDATE", NOW, "TEST", 2, 2, 0, "INPUTS");
        when(futures.evaluateAll(NOW)).thenReturn(List.of(row, row));
        var report = create(futures).inspect(NOW);
        assertEquals(0, report.distinctEvaluatorsObserved());
        assertEquals(0, report.liveExecutionValidated());
    }

    @Test
    void evenResearchCandidateIsNotPaperOrLiveAuthorization() {
        var futures = mock(FnoFuturesResearchService.class);
        when(futures.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-171",
                "RESEARCH_CANDIDATE", "SOURCE_SYNCHRONIZED", NOW, "TEST", 2, 2, 0, "PAIR")));
        var report = create(futures).inspect(NOW);
        assertEquals(1, report.distinctEvaluatorsObserved());
        assertEquals(0, report.independentlyVerifiedSourceStrategies());
        assertFalse(report.strategies().get(1).paperExecutionValidated());
        assertFalse(report.strategies().get(1).liveExecutionValidated());
    }

    @Test
    void failedOptionSourceDoesNotEraseSeparatelyVerifiedFuturesResearchStatus() {
        var futures = mock(FnoFuturesResearchService.class);
        when(futures.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-170",
                "BLOCKED_DATA", "SPOT_NOT_VERIFIED", NOW, "NONE", 1, 0, 0, "NO_SPOT")));
        var options = mock(FnoOptionsResearchService.class);
        when(options.evaluateAll(NOW)).thenThrow(
            new IllegalStateException("secret broker token"));
        var report = new Fno43StrategyReadinessService(
            futures, options, new ObjectMapper()).inspect(NOW);
        assertEquals(1, report.distinctEvaluatorsObserved());
        assertTrue(report.strategies().stream()
            .anyMatch(r -> r.strategyId().equals("strategy.STR-170")
                && r.distinctEvaluatorObserved()));
        assertTrue(report.strategies().stream()
            .filter(r -> r.strategyId().equals("strategy.STR-181")
                      || r.strategyId().equals("strategy.STR-182"))
            .allMatch(r -> "DIRECT_ADAPTER_UNAVAILABLE".equals(r.integrationState())
                && !r.reasonCode().contains("secret")));
        assertEquals(0, report.paperExecutionValidated());
        assertEquals(0, report.liveExecutionValidated());
    }

    @Test
    void debitSpreadSourceFailureCannotEraseIndependentLongOptionsOrFuturesEvidence() {
        var futures = mock(FnoFuturesResearchService.class);
        when(futures.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-170",
                "BLOCKED_DATA", "SPOT_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "NO_SPOT")));
        var options = mock(FnoOptionsResearchService.class);
        when(options.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-181",
                "BLOCKED_RIGHTS", "LONG_CALL_SOURCE_RIGHTS_UNVERIFIED",
                NOW, "NONE", 0, 0, 0, "NIFTY_OPTION_SOURCE_BLOCKED")));
        var spreads = mock(FnoDebitSpreadResearchService.class);
        when(spreads.evaluateAll(NOW)).thenThrow(
            new IllegalStateException("secret broker option source"));
        var report = new Fno43StrategyReadinessService(
            futures, options, spreads, new ObjectMapper()).inspect(NOW);
        assertEquals(2, report.distinctEvaluatorsObserved());
        assertTrue(report.strategies().stream().filter(r ->
            r.strategyId().equals("strategy.STR-183")
             || r.strategyId().equals("strategy.STR-184"))
            .allMatch(r -> "DIRECT_ADAPTER_UNAVAILABLE".equals(r.integrationState())
                && !r.reasonCode().contains("secret")));
        assertEquals(0, report.paperExecutionValidated());
        assertEquals(0, report.liveExecutionValidated());
    }

    @Test
    void creditSourceExceptionsNeverHideOtherStrategyReadinessOrExposeBrokerBalance() {
        var futures = mock(FnoFuturesResearchService.class);
        when(futures.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-170",
                "BLOCKED_DATA", "SPOT_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "NO_SPOT")));
        var options = mock(FnoOptionsResearchService.class);
        var debit = mock(FnoDebitSpreadResearchService.class);
        var credit = mock(FnoCreditSpreadResearchService.class);
        when(credit.evaluateAll(NOW)).thenThrow(
            new IllegalStateException("private bank cash and broker token"));
        var report = new Fno43StrategyReadinessService(
            futures, options, debit, credit, new ObjectMapper()).inspect(NOW);
        assertEquals(1, report.distinctEvaluatorsObserved());
        assertTrue(report.strategies().stream().filter(r ->
            r.strategyId().equals("strategy.STR-185")
            || r.strategyId().equals("strategy.STR-186")).allMatch(
                r -> "DIRECT_ADAPTER_UNAVAILABLE".equals(r.integrationState())
                && !r.reasonCode().contains("private")));
        assertEquals(0, report.paperExecutionValidated());
        assertEquals(0, report.liveExecutionValidated());
    }

    @Test
    void settledHoldingsProviderFailuresCannotOverwriteIndependentFuturesAndSpreadProof() {
        var futures = mock(FnoFuturesResearchService.class);
        when(futures.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-170",
                "BLOCKED_DATA", "SPOT_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "NO_SPOT")));
        var options = mock(FnoOptionsResearchService.class);
        var debit = mock(FnoDebitSpreadResearchService.class);
        var credit = mock(FnoCreditSpreadResearchService.class);
        var owned = mock(FnoOwnedEquityOptionsResearchService.class);
        when(owned.evaluateAll(NOW)).thenThrow(
            new IllegalStateException("private account and settled share records"));
        var report = new Fno43StrategyReadinessService(
            futures,options,debit,credit,owned,new ObjectMapper()).inspect(NOW);
        assertEquals(1,report.distinctEvaluatorsObserved());
        assertTrue(report.strategies().stream().filter(r ->
            r.strategyId().equals("strategy.STR-187")
            || r.strategyId().equals("strategy.STR-188")).allMatch(r ->
                "DIRECT_ADAPTER_UNAVAILABLE".equals(r.integrationState())
                && !r.reasonCode().contains("private")));
        assertEquals(0,report.paperExecutionValidated());
        assertEquals(0,report.liveExecutionValidated());
    }

    @Test
    void adminEndpointIsRoleRestrictedAndNoStore() throws Exception {
        var futures = mock(FnoFuturesResearchService.class);
        when(futures.evaluateAll(NOW)).thenReturn(List.of());
        var controller = new AdminFno43ReadinessController(
            create(futures), Clock.fixed(NOW, ZoneOffset.UTC));
        var response = controller.readiness();
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertEquals(NOW, response.getBody().generatedAt());
        assertEquals(43, response.getBody().manifestStrategies());
        var policy = AdminFno43ReadinessController.class.getMethod("readiness")
            .getAnnotation(PreAuthorize.class);
        assertNotNull(policy);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')", policy.value());
    }
}
