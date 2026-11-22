package in.aitradingsystem.tradingrooms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Bounded original SOURCE research replay for STR-187..212 (26 strategies).
 * These are the EXISTING rights-joined original-source research services, not
 * synthetic backtest processors or broker orders. Their source SQL receipts,
 * as-of first-known timestamps, market-depth/IV/Greeks/account holdings and
 * trading calendar gates remain mandatory and strategy-specific.
 *
 * Historical source-to-evaluator invocation != independently accepted
 * after-cost out-of-sample history, PAPER ledger or LIVE broker execution.
 */
@Service
public final class Fno187212OriginalSourceReplayService {
    public static final String CONTRACT = "minttick.fno.187-212.original-source-asof-replay.v1";
    private static final int MAX_WINDOWS = 16;
    private static final Map<Integer,String> SOURCE_STATES = Map.ofEntries(
        Map.entry(187,"SETTLED_EQUITY_PROTECTIVE_PUT_RESEARCH_NO_ORDER"),
        Map.entry(188,"SETTLED_EQUITY_COVERED_CALL_RESEARCH_NO_ORDER"),
        Map.entry(189,"BROKER_SETTLED_EQUITY_COLLAR_SOURCE_RESEARCH_NO_ORDER"),
        Map.entry(190,"CALL_RATIO_UNBOUNDED_TAIL_RESEARCH_NO_ORDER"),
        Map.entry(191,"PUT_RATIO_FINITE_EXPIRY_TAIL_RESEARCH_NO_ORDER"),
        Map.entry(192,"ORIGINAL_PER_LEG_IV_SOURCE_RESEARCH_ONLY_NO_ORDER"),
        Map.entry(193,"ORIGINAL_PER_LEG_IV_SOURCE_RESEARCH_ONLY_NO_ORDER"),
        Map.entry(194,"SHORT_STRADDLE_UNBOUNDED_TAIL_RESEARCH_NO_ORDER"),
        Map.entry(195,"SHORT_STRANGLE_UNBOUNDED_TAIL_RESEARCH_NO_ORDER"),
        Map.entry(196,"ORIGINAL_FOUR_LEG_SOURCE_DEFINED_EXPIRY_RISK_ONLY_NO_ORDER"),
        Map.entry(197,"ORIGINAL_FOUR_LEG_SOURCE_DEFINED_EXPIRY_RISK_ONLY_NO_ORDER"),
        Map.entry(198,"ORIGINAL_THREE_CALL_TWO_CENTER_SHORT_LOTS_NO_ORDER"),
        Map.entry(199,"ORIGINAL_DIFFERENT_EXPIRIES_FAR_MARK_UNRESOLVED_NO_ORDER"),
        Map.entry(200,"ORIGINAL_DIFFERENT_EXPIRIES_FAR_MARK_UNRESOLVED_NO_ORDER"),
        Map.entry(201,"ORIGINAL_30_DAY_ATM_IV_200_SOURCE_ALIGNED_CANDLES_NO_ORDER"),
        Map.entry(202,"ORIGINAL_OFFICIAL_EVENT_PRE_POST_30D_IV_OBSERVATION_RETROSPECTIVE_NO_ORDER"),
        Map.entry(203,"ORIGINAL_GREEKS_FUTURES_GAMMA_PATH_RETROSPECTIVE_NO_FILLS"),
        Map.entry(204,"BROKER_CONFIRMED_POSITIONS_ORIGINAL_DELTA_FUTURES_HYPOTHETICAL_NO_ORDER"),
        Map.entry(205,"BROKER_VERIFIED_HOLDINGS_ORIGINAL_PORTFOLIO_BETA_PROXY_NO_ORDER"),
        Map.entry(206,"BROKER_VERIFIED_HOLDINGS_ORIGINAL_TAIL_STRESS_HINDSIGHT_ONLY_NO_ORDER"),
        Map.entry(207,"BROKER_VERIFIED_LONG_FUTURE_PUT_WING_SHORT_CALL_UNCAPPED_TAIL_NO_ORDER"),
        Map.entry(208,"BROKER_EXISTING_VERTICAL_FOUR_LEG_ROLL_RESEARCH_NO_ACTUAL_FILLS"),
        Map.entry(209,"OFFICIAL_EXPIRY_CUTOFF_COMPLETE_BROKER_BOOK_HYPOTHETICAL_CLOSE_NO_ORDER"),
        Map.entry(210,"BROKER_HOLDINGS_THREE_ORIGINAL_TAIL_DAYS_TWO_PUTS_BASIS_RISK_UNMODELED"),
        Map.entry(211,"COMPLETE_BROKER_OPTIONS_BOOK_SOURCE_GREEKS_BUY_ONLY_NOT_ORDERS"),
        Map.entry(212,"SOURCE_ORIGINAL_DELTA_MATCHED_PUT_CALL_SKEW_UNCAPPED_DOWNSIDE_NO_ORDER")
    );
    private final Map<Integer,Function<Instant,List<FnoFuturesResearchService.StrategyStatus>>> readers;
    private final Clock clock;

    @Autowired
    public Fno187212OriginalSourceReplayService(
            FnoOwnedEquityOptionsResearchService s1,
            FnoEquityCollarResearchService s2,
            FnoRatioSpreadResearchService s3,
            FnoStraddleResearchService s4,
            FnoStrangleResearchService s5,
            FnoShortStraddleResearchService s6,
            FnoShortStrangleResearchService s7,
            FnoIronCondorResearchService s8,
            FnoIronButterflyResearchService s9,
            FnoLongButterflyResearchService s10,
            FnoCalendarSpreadResearchService s11,
            FnoDiagonalSpreadResearchService s12,
            FnoIvRankResearchService s13,
            FnoVolatilityCrushResearchService s14,
            FnoGammaScalpingResearchService s15,
            FnoDeltaHedgeResearchService s16,
            FnoBetaHedgeOptionsResearchService s17,
            FnoTailRiskPutHedgeResearchService s18,
            FnoPutSpreadCollarResearchService s19,
            FnoDefinedRiskSpreadRollResearchService s20,
            FnoExpiryRiskNeutralizationResearchService s21,
            FnoPortfolioProtectiveOverlayResearchService s22,
            FnoGreeksExposureRebalanceResearchService s23,
            FnoSkewBasedHedgeResearchService s24) {
        this(Map.ofEntries(
            Map.entry(187,at -> s1.evaluateAll(at)),
            Map.entry(188,at -> s1.evaluateAll(at)),
            Map.entry(189,at -> List.of(s2.evaluate(at))),
            Map.entry(190,at -> s3.evaluateAll(at)),
            Map.entry(191,at -> s3.evaluateAll(at)),
            Map.entry(192,at -> List.of(s4.evaluate(at))),
            Map.entry(193,at -> List.of(s5.evaluate(at))),
            Map.entry(194,at -> List.of(s6.evaluate(at))),
            Map.entry(195,at -> List.of(s7.evaluate(at))),
            Map.entry(196,at -> List.of(s8.evaluate(at))),
            Map.entry(197,at -> List.of(s9.evaluate(at))),
            Map.entry(198,at -> List.of(s10.evaluate(at))),
            Map.entry(199,at -> List.of(s11.evaluate(at))),
            Map.entry(200,at -> List.of(s12.evaluate(at))),
            Map.entry(201,at -> List.of(s13.evaluate(at))),
            Map.entry(202,at -> List.of(s14.evaluate(at))),
            Map.entry(203,at -> List.of(s15.evaluate(at))),
            Map.entry(204,at -> List.of(s16.evaluate(at))),
            Map.entry(205,at -> List.of(s17.evaluate(at))),
            Map.entry(206,at -> List.of(s18.evaluate(at))),
            Map.entry(207,at -> List.of(s19.evaluate(at))),
            Map.entry(208,at -> List.of(s20.evaluate(at))),
            Map.entry(209,at -> List.of(s21.evaluate(at))),
            Map.entry(210,at -> List.of(s22.evaluate(at))),
            Map.entry(211,at -> List.of(s23.evaluate(at))),
            Map.entry(212,at -> List.of(s24.evaluate(at)))
        ),Clock.systemUTC());
    }

    Fno187212OriginalSourceReplayService(
            Map<Integer,Function<Instant,List<FnoFuturesResearchService.StrategyStatus>>> readers,
            Clock clock) {
        if (readers == null || !readers.keySet().equals(SOURCE_STATES.keySet())
                || readers.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("SOURCE_ADAPTERS_INCOMPLETE");
        }
        this.readers=Map.copyOf(readers);
        this.clock=Objects.requireNonNull(clock);
    }

    public record Proof(
        String strategyId,Instant asOf,String state,String reasonCode,
        String evaluatorStatus,int originalCompletedSessions,
        int originalQuotesObserved,boolean independentlyValidatedHistoricalE2E,
        boolean paperLedgerE2E,boolean liveBrokerE2E,boolean ordersPlaced) {}
    public record Replay(
        String contractVersion,String strategyId,String state,String reasonCode,
        int windowsExamined,int candidates,int noSignals,int blocked,
        String deterministicSummaryHash,boolean independentlyValidatedHistoricalE2E,
        boolean paperLedgerE2E,boolean liveBrokerE2E,boolean ordersPlaced,
        List<Proof> results) {}

    public static List<String> supportedStrategies() {
        return SOURCE_STATES.keySet().stream().sorted().map(n -> "strategy.STR-"+n).toList();
    }

    public Proof inspect(String strategyId,Instant asOf) {
        int n=number(strategyId);
        if(n<0) return denied(strategyId,asOf,"STRATEGY_OUTSIDE_ORIGINAL_REPLAY_SCOPE");
        if (asOf == null || asOf.isAfter(clock.instant())
                || asOf.isBefore(clock.instant().minus(366,ChronoUnit.DAYS))) {
            return denied(strategyId,asOf,"HISTORICAL_DECISION_TIME_INVALID");
        }
        final List<FnoFuturesResearchService.StrategyStatus> all;
        try {
            all=readers.get(n).apply(asOf);
        } catch (RuntimeException sourceUnavailable) {
            // Never return/log source exceptions: may contain private broker
            // account IDs, positions, market quotes or provider credentials.
            return denied(strategyId,asOf,"ORIGINAL_SOURCE_READ_OR_EVALUATOR_FAILED");
        }
        if (all == null || all.size() < 1 || all.size() > 2) {
            return denied(strategyId,asOf,"ORIGINAL_SOURCE_ROWS_MISSING_OR_AMBIGUOUS");
        }
        List<FnoFuturesResearchService.StrategyStatus> matches=all.stream()
            .filter(row -> row != null && strategyId.equals(row.strategyId())).toList();
        if(matches.size()!=1) return denied(strategyId,asOf,"ORIGINAL_SOURCE_STRATEGY_NOT_UNIQUE");
        var found=matches.getFirst();
        if(!asOf.equals(found.evaluatedAt()) ||
                !SOURCE_STATES.get(n).equals(found.inputState()) ||
                !("UPSTOX_ANALYTICS".equals(found.quoteSource()) ||
                  "ANGEL_ONE_SMARTAPI".equals(found.quoteSource())) ||
                !("RESEARCH_CANDIDATE".equals(found.status()) ||
                  "NO_SIGNAL".equals(found.status())) ||
                found.reasonCode()==null ||
                !found.reasonCode().matches("[A-Z0-9_]{4,128}") ||
                found.completedHistorySessions()<0 || found.completedHistorySessions()>512 ||
                found.freshQuotes()<0 || found.freshQuotes()>64) {
            return denied(strategyId,asOf,"ORIGINAL_SOURCE_OR_EVALUATOR_EVIDENCE_UNVERIFIED");
        }
        return new Proof(strategyId,asOf,"SOURCE_RESEARCH_EVALUATOR_RAN_NOT_E2E",
            found.reasonCode(),found.status(),found.completedHistorySessions(),
            found.freshQuotes(),false,false,false,false);
    }

    public Replay replay(String strategyId,List<Instant> decisionTimes) {
        if (number(strategyId)<0) return blocked(strategyId,"STRATEGY_OUTSIDE_ORIGINAL_REPLAY_SCOPE");
        if(decisionTimes==null||decisionTimes.isEmpty()||decisionTimes.size()>MAX_WINDOWS)
            return blocked(strategyId,"HISTORICAL_WINDOW_COUNT_OUT_OF_SCOPE");
        Instant prior=null;
        for(Instant decision:decisionTimes) {
            if(decision==null || decision.isAfter(clock.instant())
                    || decision.isBefore(clock.instant().minus(366,ChronoUnit.DAYS))
                    || (prior!=null&&!decision.isAfter(prior))) {
                return blocked(strategyId,"HISTORICAL_WINDOWS_NOT_MONOTONIC_ASOF");
            }
            prior=decision;
        }
        int candidates=0,quiet=0,blocked=0;
        List<Proof> outputs=new ArrayList<>();
        for(var decision:decisionTimes) {
            var result=inspect(strategyId,decision);
            outputs.add(result);
            if("RESEARCH_CANDIDATE".equals(result.evaluatorStatus()))candidates++;
            else if("NO_SIGNAL".equals(result.evaluatorStatus()))quiet++;
            else blocked++;
        }
        var safe=List.copyOf(outputs);
        return new Replay(CONTRACT,strategyId,blocked==0
                ?"ORIGINAL_SOURCE_REPLAY_RESEARCH_ONLY_NOT_E2E"
                :"HISTORICAL_SOURCE_REPLAY_INCOMPLETE",
            blocked==0?"INDEPENDENT_AFTER_COST_OOS_PROOF_MISSING"
                :"ORIGINAL_RIGHTS_DATA_OR_ACCOUNT_PROOF_UNAVAILABLE",
            outputs.size(),candidates,quiet,blocked,hash(safe),
            false,false,false,false,safe);
    }

    private static int number(String id) {
        if(id==null || !id.matches("strategy\\.STR-(18[7-9]|19[0-9]|20[0-9]|21[0-2])"))
            return -1;
        int n=Integer.parseInt(id.substring("strategy.STR-".length()));
        return SOURCE_STATES.containsKey(n)?n:-1;
    }

    private static Proof denied(String id,Instant at,String reason) {
        return new Proof(id,at,"BLOCKED_NOT_HISTORICAL_E2E",reason,"NOT_RUN",
            0,0,false,false,false,false);
    }
    private static Replay blocked(String id,String reason) {
        return new Replay(CONTRACT,id,"BLOCKED_NOT_HISTORICAL_E2E",reason,
            0,0,0,0,null,false,false,false,false,List.of());
    }
    /** Local deterministic summary digest, NOT independently signed source custody. */
    private static String hash(List<Proof> proofs) {
        try{
            var digest=MessageDigest.getInstance("SHA-256");
            for(var p:proofs) {
                String row=p.strategyId()+"|"+p.asOf()+"|"+p.state()+"|"+
                    p.reasonCode()+"|"+p.evaluatorStatus()+"|"+
                    p.originalCompletedSessions()+"|"+
                    p.originalQuotesObserved()+"\n";
                digest.update(row.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){
            throw new IllegalStateException("HASH_ALGORITHM_UNAVAILABLE",impossible);
        }
    }
}
