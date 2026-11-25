package in.aitradingsystem.instrument;

import org.springframework.stereotype.Service;

/**
 * Resolves instrument metadata for read-only research. Active catalogue identity remains preferred;
 * only a genuinely missing active row may fall back to durable NSE/BSE cash-equity history.
 */
@Service
public class ResearchInstrumentResolver {
    private final InstrumentMasterService activeInstruments;
    private final InstrumentCatalogueResearchRepository researchRepository;

    public ResearchInstrumentResolver(InstrumentMasterService activeInstruments,
                                      InstrumentCatalogueResearchRepository researchRepository) {
        this.activeInstruments = activeInstruments;
        this.researchRepository = researchRepository;
    }

    public Instrument requireForResearch(long instrumentId) {
        try {
            return activeInstruments.requireActiveInstrument(instrumentId);
        } catch (InstrumentMasterException ex) {
            if (!"INSTRUMENT_NOT_FOUND".equals(ex.code())) {
                throw ex;
            }
            Instrument historical = researchRepository.findHistoricalCashInstrument(instrumentId);
            if (historical != null) {
                return historical;
            }
            throw ex;
        }
    }
}
