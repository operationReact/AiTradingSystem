package in.aitradingsystem.api;

import in.aitradingsystem.paper.PaperQueryService;
import in.aitradingsystem.paper.PaperViews.EquityPoint;
import in.aitradingsystem.paper.PaperViews.PortfolioView;
import in.aitradingsystem.provider.angelone.AngelOneHealthService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@Validated
public class DashboardController {
    private final PaperQueryService queries;
    private final boolean geminiConfigured;
    private final AngelOneHealthService angelOne;

    public DashboardController(PaperQueryService queries,
                               @Value("${app.providers.gemini.api-key:}") String geminiKey,
                               AngelOneHealthService angelOne) {
        this.queries = queries;
        this.geminiConfigured = geminiKey != null && !geminiKey.isBlank();
        this.angelOne = angelOne;
    }

    public record AgentStatus(String id, String name, String status, String description) {}

    public record ProviderStatus(String name, String status, Instant lastSuccessfulCheck) {}

    public record DashboardSummary(String mode, boolean liveTradingEnabled, boolean newOrdersHalted,
        BigDecimal capital, BigDecimal availableCash, BigDecimal investedCapital, BigDecimal marketValue,
        BigDecimal equity, BigDecimal realizedPnl, BigDecimal unrealizedPnl, BigDecimal dailyPnl,
        int openPositions, BigDecimal exposurePercent, boolean cashReconciled, String markPriceSource,
        String systemStatus, Instant asOf, List<AgentStatus> agents, List<ProviderStatus> providers) {}

    @GetMapping("/summary")
    public DashboardSummary summary() {
        PortfolioView p = queries.portfolio();
        List<AgentStatus> agents = List.of(
            new AgentStatus("news", "News Ingestion", "NOT_CONFIGURED", "No news provider adapter is configured."),
            new AgentStatus("scanner", "Market Scanner", "NOT_CONFIGURED", "No market-data adapter is configured; no live quotes are shown."),
            new AgentStatus("strategy", "Strategy Analyst", "DEMO", "Signals shown are synthetic demo data."),
            new AgentStatus("risk", "Risk Manager", "ACTIVE", "Deterministic checks run on every paper order."),
            new AgentStatus("execution", "Paper Execution", "ACTIVE", "Paper-only fills persisted to PostgreSQL."));
        List<ProviderStatus> providers = List.of(
            new ProviderStatus("Gemini", geminiConfigured ? "CONFIGURED_UNVERIFIED" : "NOT_CONFIGURED", null),
            new ProviderStatus("Angel One SmartAPI", angelOne.status().state().name(), null));
        String systemStatus = p.cashReconciled() ? (p.newOrdersHalted() ? "HALTED" : "OK") : "RECONCILIATION_FAILED";
        return new DashboardSummary(p.mode(), p.liveTradingEnabled(), p.newOrdersHalted(), p.startingCapital(),
            p.cash(), p.invested(), p.marketValue(), p.equity(), p.realizedPnl(), p.unrealizedPnl(),
            p.realizedPnlToday(), p.openPositions(), p.exposurePercent(), p.cashReconciled(), p.markPriceSource(),
            systemStatus, p.asOf(), agents, providers);
    }

    @GetMapping("/equity-curve")
    public List<EquityPoint> equityCurve(@RequestParam(defaultValue = "90") @Min(1) @Max(365) int days) {
        return queries.equityCurve(days);
    }
}