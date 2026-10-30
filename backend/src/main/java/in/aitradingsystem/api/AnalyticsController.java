package in.aitradingsystem.api;

import in.aitradingsystem.analytics.*;
import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import in.aitradingsystem.common.ApiError;
import in.aitradingsystem.common.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final PortfolioAnalyticsService portfolio;
    private final StrategyAnalyticsService strategies;
    private final ResearchAnalyticsService research;
    private final PerformanceAggregationService aggregation;
    private final Clock clock;
    public AnalyticsController(PortfolioAnalyticsService portfolio, StrategyAnalyticsService strategies,
        ResearchAnalyticsService research, PerformanceAggregationService aggregation, Clock clock) {
        this.portfolio=portfolio; this.strategies=strategies; this.research=research; this.aggregation=aggregation; this.clock=clock;
    }

    @GetMapping("/portfolio")
    public PortfolioAnalyticsService.PortfolioAnalytics portfolio(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) { return portfolio.summarize(period(from,to)); }

    @GetMapping("/equity")
    public EquityView equity(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        var result=portfolio.summarize(period(from,to));
        return new EquityView(result.period(),result.materializationComplete(),result.equityCurve(),result.dailyCloseDrawdown());
    }

    @GetMapping("/strategies")
    public StrategyView strategies(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        var period=period(from,to); return new StrategyView(period,strategies.summarize(period));
    }

    @GetMapping("/research")
    public ResearchAnalyticsService.ResearchAnalytics research(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) { return research.summarize(period(from,to)); }

    @PostMapping("/portfolio/recalculate")
    public ResponseEntity<?> recalculate(@Valid @RequestBody RecalculateRequest request) {
        try { return ResponseEntity.ok(aggregation.recalculate(request.tradeDate())); }
        catch (IllegalStateException ex) {
            String code=ex.getMessage()==null?"":ex.getMessage().split(":",2)[0];
            if (!java.util.Set.of("PERFORMANCE_MARK_UNAVAILABLE","PERFORMANCE_LEDGER_MISMATCH","PERFORMANCE_ATTRIBUTION_UNAVAILABLE").contains(code)) throw ex;
            String message="Required accounting, attribution or valuation evidence is unavailable. Review source data before retrying.";
            ApiError error=new ApiError(clock.instant(),409,code,message,"Performance materialization unavailable",message,
                "/api/analytics/portfolio/recalculate",MDC.get(CorrelationIdFilter.MDC_KEY),List.of(code));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }
    }

    private AnalyticsPeriod period(LocalDate from,LocalDate to) {
        LocalDate end=to==null?LocalDate.now(clock.withZone(IndianCashMarketCalendar.ZONE)).minusDays(1):to;
        return new AnalyticsPeriod(from==null?end.minusDays(29):from,end);
    }
    public record RecalculateRequest(@NotNull LocalDate tradeDate) {}
    public record EquityView(AnalyticsPeriod period,boolean materializationComplete,
        List<PortfolioAnalyticsService.EquityPoint> equityCurve,List<PortfolioAnalyticsService.DrawdownPoint> dailyCloseDrawdown) {}
    public record StrategyView(AnalyticsPeriod period,List<StrategyAnalyticsService.StrategyPerformance> items) {}
}
