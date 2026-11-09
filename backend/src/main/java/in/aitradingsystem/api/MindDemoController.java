package in.aitradingsystem.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opt-in, read-only demonstration contract for the Mintick Mind Flutter UI.
 *
 * Profile "mind-demo" is NOT active by default. The security chain still
 * requires an authenticated role for GET /api/**. This controller exposes no
 * order, risk-override, broker, prediction, or mutation endpoint.
 *
 * These samples must never be represented as verified Indian-market data.
 */
@RestController
@Profile("mind-demo")
@RequestMapping("/api/mind/demo/v1")
public class MindDemoController {
    private static Map<String,Object> envelope(String screen, Object data) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("screen",screen);
        result.put("demo",true);
        result.put("verified",false);
        result.put("executionEnabled",false);
        result.put("source","SYNTHETIC_OFFLINE_FIXTURES");
        result.put("generatedFor","2026-11-09");
        result.put("data",data);
        return result;
    }

    @GetMapping("/intelligence-hub")
    public Map<String,Object> hub() {
        return envelope("intelligence-hub",Map.of(
          "posture","SELECTIVE","agentAgreement","3/5",
          "calibration","UNVERIFIED","capitalAuthority","LOCKED",
          "pipeline",List.of("source-validation","regime-router","signal-scoring",
            "agent-council","risk-veto","broker-disabled","outcome-audit"),
          "horizons",List.of("Intraday","Swing","Positional","Delivery",
            "Long-Term","Cash Short","Futures","Options")));
    }

    @GetMapping("/agent-council")
    public Map<String,Object> council() {
        return envelope("agent-council",Map.of(
          "symbol","CDSL","posture","REVIEW","weightedScore",62,
          "probabilityCalibrated",false,
          "votes",List.of(
            Map.of("role","Bull researcher","position","BUY CANDIDATE","weight",26,
              "score",78,"reason","Relative strength and mock volume impulse"),
            Map.of("role","Bear researcher","position","WAIT","weight",22,
              "score",46,"reason","Price extension and cost-adjusted downside"),
            Map.of("role","Fundamental researcher","position","RESEARCH","weight",22,
              "score",66,"reason","Valuation and official filings remain incomplete"),
            Map.of("role","Regime & institutional flow","position","WAIT","weight",17,
              "score",58,"reason","Participation and institutional evidence unavailable"),
            Map.of("role","Skeptical auditor","position","ABSTAIN CHECK","weight",13,
              "score",53,"reason","Correlation, freshness and permissions unverified"))));
    }

    @GetMapping("/agent-evidence")
    public Map<String,Object> evidence() {
        return envelope("agent-evidence",List.of(
          Map.of("id","EVD-001","category","Market evidence","symbol","RELIANCE",
            "claim","Price trend","knownAt","Mock 09:22:05","status","Synthetic"),
          Map.of("id","EVD-002","category","Market evidence","symbol","CDSL",
            "claim","Volume participation","knownAt","Mock 09:22:02","status","Synthetic"),
          Map.of("id","EVD-003","category","Company evidence","symbol","POLYCAB",
            "claim","Corporate filing","knownAt","Not verified","status","Unavailable"),
          Map.of("id","EVD-004","category","Institutional","symbol","NIFTY50",
            "claim","Net flow","knownAt","Not verified","status","Unavailable"),
          Map.of("id","EVD-005","category","Model output","symbol","NIFTY50",
            "claim","Regime classification","knownAt","Mock 09:22:05","status","Synthetic"),
          Map.of("id","EVD-006","category","Capital authority","symbol","NTPC",
            "claim","Broker eligibility","knownAt","Not connected","status","Unavailable"),
          Map.of("id","EVD-007","category","Learning","symbol","CDSL",
            "claim","Forward outcome label","knownAt","Mock +5d","status","Synthetic"))));
    }

    @GetMapping("/regime-weights")
    public Map<String,Object> weights() {
        return envelope("regime-weights",Map.of(
          "regimes", Map.of(
            "Trending Up",List.of(42,20,26,12),
            "Range Bound",List.of(12,33,28,27),
            "Trending Down",List.of(6,26,24,44),
            "High Volatility",List.of(10,20,25,45),
            "Data Uncertain",List.of(0,0,0,0)),
          "families",List.of("Momentum","Fundamentals","Mean Reversion","Defensive / Abstain"),
          "weightsAreOptimized",false,
          "unknownDataAction","ABSTAIN"));
    }

    @GetMapping("/signal-scorecard")
    public Map<String,Object> scorecard() {
        return envelope("signal-scorecard",Map.of(
          "horizon","Swing","verifiedBacktest",false,"costsVerified",false,
          "rows",List.of(
            Map.of("family","Momentum Breakout","winRate",66,"profitFactor",1.22),
            Map.of("family","VWAP Recovery","winRate",54,"profitFactor",1.31),
            Map.of("family","Earnings Catalyst","winRate",59,"profitFactor",1.40),
            Map.of("family","Quality Compounder","winRate",65,"profitFactor",1.49),
            Map.of("family","Bear Reversal","winRate",49,"profitFactor",1.58))));
    }

    @GetMapping("/confidence-lab")
    public Map<String,Object> calibration() {
        return envelope("confidence-lab",Map.of(
          "horizon","Swing","calibrated",false,"brierScore", "NOT_MEASURED",
          "buckets",List.of(
            Map.of("forecast",45,"mockObserved",43,"sample",28),
            Map.of("forecast",55,"mockObserved",47,"sample",37),
            Map.of("forecast",65,"mockObserved",56,"sample",31),
            Map.of("forecast",75,"mockObserved",61,"sample",22),
            Map.of("forecast",85,"mockObserved",66,"sample",9))));
    }

    @GetMapping("/shadow-arena")
    public Map<String,Object> shadow() {
        return envelope("shadow-arena",Map.of(
          "comparison","Baseline versus Adaptive",
          "baselineIndex",100.0,
          "challengerIndex",103.2,
          "brokerFills",0,
          "allOutcomesSynthetic",true,
          "requiredGates",List.of("identical known-at cutoff","same budget",
            "costs","no future labels","include abstentions")));
    }

    @GetMapping("/institutional-flow")
    public Map<String,Object> institutionalFlow() {
        return envelope("institutional-flow",Map.of(
          "date","2026-11-09",
          "officialSourceConnected",false,
          "fiiNetCr",-1420,
          "diiNetCr",1810,
          "niftyBreadthPercent",43,
          "currency","INR",
          "warning","FIIs, DIIs and breadth are invented sample values."));
    }

    @GetMapping("/daily-verdict")
    public Map<String,Object> dailyVerdict() {
        return envelope("daily-verdict",Map.of(
          "date","2026-11-09","posture","SELECTIVE",
          "regime","elevated uncertainty","verifiedEntries",0,
          "authorization","NO LIVE TRADING",
          "watchpoints",List.of("CDSL","TITAN","POLYCAB","NIFTY50"),
          "advice",false));
    }

    @GetMapping("/culprit-catcher")
    public Map<String,Object> incidents() {
        return envelope("culprit-catcher",List.of(
          Map.of("id","INC-102","symbol","CDSL","severity","High",
            "kind","False breakout","stage","Signal validation",
            "cause","Correlated momentum and volume double-counted"),
          Map.of("id","INC-103","symbol","NTPC","severity","Low",
            "kind","Short-sale abstention","stage","Eligibility gate",
            "cause","Product and broker permissions unverified"),
          Map.of("id","INC-104","symbol","POLYCAB","severity","Medium",
            "kind","Thesis drift","stage","Long-term valuation",
            "cause","Margin-of-safety and governance checks incomplete"),
          Map.of("id","INC-105","symbol","TCS","severity","Medium",
            "kind","Regime mismatch","stage","Regime router",
            "cause","Short-term signal conflicted with higher timeframe"))));
    }
}
