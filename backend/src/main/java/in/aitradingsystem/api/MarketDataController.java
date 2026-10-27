package in.aitradingsystem.api;

import in.aitradingsystem.marketdata.InstrumentMasterService;
import in.aitradingsystem.marketdata.MarketInstrument;
import in.aitradingsystem.marketdata.MarketDataInstrumentRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/market-data")
public class MarketDataController {
    private final InstrumentMasterService masterService;
    private final MarketDataInstrumentRepository instruments;

    public MarketDataController(InstrumentMasterService masterService, MarketDataInstrumentRepository instruments) {
        this.masterService = masterService;
        this.instruments = instruments;
    }

    /** Manually refreshes the bounded public instrument master. Protected by the operator API key. */
    @PostMapping("/instruments/sync")
    public InstrumentMasterService.SyncResult syncInstrumentMaster() {
        return masterService.sync();
    }

    @GetMapping("/instruments")
    public List<MarketInstrument> searchInstruments(
        @RequestParam(defaultValue = "") String query,
        @RequestParam(required = false) @Pattern(regexp = "NSE|BSE") String exchange,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        if (query.length() > 100) throw new IllegalArgumentException("query must be at most 100 characters");
        return instruments.search(query, exchange, limit);
    }
}