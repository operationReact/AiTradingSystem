package in.aitradingsystem.marketdata;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator-triggered reconciliation of stored market data; does not place broker orders. */
@RestController
@RequestMapping("/api/market-data")
public class MarketDataReconciliationController {
    private final MarketDataReconciliationService reconciliation;

    public MarketDataReconciliationController(MarketDataReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/reconcile")
    public ReconciliationResult reconcile(@RequestParam long instrumentId,
            @RequestParam HistoricalCandleInterval interval,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (instrumentId <= 0) throw new IllegalArgumentException("instrumentId must be positive");
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from");
        }
        return reconciliation.reconcile(instrumentId, interval.name(), from, to);
    }
}
