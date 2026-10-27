package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.InstrumentImportSummary;
import in.aitradingsystem.instrument.InstrumentMasterService;
import org.springframework.stereotype.Service;

/**
 * Compatibility facade for the market-data API. The versioned instrument catalogue owns validation,
 * import auditing, duplicate detection, and atomic publication; do not maintain a second catalogue here.
 */
@Service
public class PublicInstrumentMasterSyncService {
    private final InstrumentMasterService instrumentService;

    public PublicInstrumentMasterSyncService(InstrumentMasterService instrumentService) {
        this.instrumentService = instrumentService;
    }

    public InstrumentImportSummary sync() {
        return instrumentService.importLatest();
    }
}
