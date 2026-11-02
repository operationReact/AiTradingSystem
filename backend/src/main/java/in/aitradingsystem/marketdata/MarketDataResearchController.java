package in.aitradingsystem.marketdata;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/market-data/research")
public class MarketDataResearchController {
    private final MarketDataResearchExportService service;

    public MarketDataResearchController(MarketDataResearchExportService service) {
        this.service = service;
    }

    @GetMapping("/candles")
    public MarketDataResearchExport candles(
            @RequestParam @Min(1) long instrumentId,
            @RequestParam HistoricalCandleInterval interval,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "20000") @Min(1) @Max(20_000) int limit) {
        return service.export(instrumentId, interval, fromDate, toDate, limit);
    }
}
