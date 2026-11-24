package in.aitradingsystem.news.feature;

import in.aitradingsystem.config.AngelOneProperties;
import in.aitradingsystem.instrument.GoldenListingIdentityRepository;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentCatalogueResearchRepository;
import in.aitradingsystem.instrument.InstrumentCatalogueResearchRepository.CatalogueResearchVersion;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Research-only bridge from one historically retained Angel One cash instrument
 * to independently verified news-to-listing evidence.
 *
 * <p>No full-universe scan or new upstream HTTP request is made. The broker
 * identity must belong to the exact catalogue available at decision time and
 * its source must be the configured allow-listed Angel One instrument master.
 * A token alone or model-generated affected-symbol is never verification.
 */
@Service
public class NewsInstrumentVerifiedEvidenceService {
    private static final String PROVIDER = "ANGEL_ONE";

    private final InstrumentCatalogueResearchRepository catalogue;
    private final GoldenListingIdentityRepository listingIdentity;
    private final NewsVerifiedListingEventEvidenceRepository events;
    private final AngelOneProperties angelOne;

    public NewsInstrumentVerifiedEvidenceService(
            InstrumentCatalogueResearchRepository catalogue,
            GoldenListingIdentityRepository listingIdentity,
            NewsVerifiedListingEventEvidenceRepository events,
            AngelOneProperties angelOne) {
        this.catalogue = catalogue;
        this.listingIdentity = listingIdentity;
        this.events = events;
        this.angelOne = angelOne;
    }

    public ResearchResult verifiedForInstrument(long instrumentId, Instant decisionAt) {
        if (instrumentId <= 0 || decisionAt == null || decisionAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Valid instrumentId and non-future decisionAt are required");
        }

        CatalogueResearchVersion catalogueVersion = catalogue.requireVersionAt(decisionAt);
        if (!Objects.equals(catalogueVersion.sourceUrl(), angelOne.instrumentMasterUrl())) {
            return result(instrumentId, decisionAt, catalogueVersion.version(),
                "UNVERIFIED_CATALOGUE_SOURCE", null, List.of());
        }
        Instrument instrument = catalogue.findHistoricalCashInstrument(instrumentId);
        if (instrument == null || !catalogueVersion.version().equals(instrument.catalogueVersion())) {
            return result(instrumentId, decisionAt, catalogueVersion.version(),
                "INSTRUMENT_NOT_IN_ASOF_CATALOGUE", null, List.of());
        }
        if (!cashEquity(instrument)) {
            return result(instrumentId, decisionAt, catalogueVersion.version(),
                "NOT_CASH_EQUITY", null, List.of());
        }

        // The existing Golden resolver rejects ambiguous provider tokens. The
        // exact-symbol variant also prevents a reused token from being silently
        // resolved to a differently named issuer/listing at the research cutoff.
        var verifiedListing = listingIdentity.resolveExactSymbolAsOf(
            PROVIDER, instrument.exchange(), instrument.symbolToken(),
            instrument.tradingSymbol(), decisionAt);
        if (verifiedListing.isEmpty()) {
            return result(instrumentId, decisionAt, catalogueVersion.version(),
                "LISTING_IDENTITY_UNVERIFIED", null, List.of());
        }
        List<NewsVerifiedListingEventEvidenceRepository.Evidence> verified =
            events.verifiedAsOf(verifiedListing.get(), decisionAt);
        return result(instrumentId, decisionAt, catalogueVersion.version(),
            verified.isEmpty() ? "VERIFIED_LISTING_NO_EVENT_EVIDENCE" : "VERIFIED_LISTING_EVENT_EVIDENCE",
            verifiedListing.get(), verified);
    }

    private static boolean cashEquity(Instrument instrument) {
        if (instrument.symbolToken() == null || instrument.symbolToken().isBlank()
                || instrument.tradingSymbol() == null || instrument.tradingSymbol().isBlank()
                || !"EQ".equalsIgnoreCase(instrument.instrumentType())
                || instrument.expiry() != null
                || (instrument.strike() != null && instrument.strike().signum() > 0)) {
            return false;
        }
        return ("NSE".equals(instrument.exchange())
                    && "NSE_CM".equals(instrument.exchangeSegment())
                    && instrument.tradingSymbol().endsWith("-EQ"))
                || ("BSE".equals(instrument.exchange())
                    && "BSE_CM".equals(instrument.exchangeSegment()));
    }

    private static ResearchResult result(long id, Instant at, UUID catalogueVersion,
            String status, UUID listingId,
            List<NewsVerifiedListingEventEvidenceRepository.Evidence> evidence) {
        return new ResearchResult(id, at, catalogueVersion, status, listingId,
            evidence.size(), List.copyOf(evidence), false);
    }

    /** canAuthorizeCapital is ALWAYS false. Neither this service nor its
     * results can grant strategy inclusion, broker permissions or order routing.
     */
    public record ResearchResult(
        long instrumentId, Instant decisionAt, UUID catalogueVersion,
        String verificationStatus, UUID verifiedListingId,
        int verifiedEventReceipts,
        List<NewsVerifiedListingEventEvidenceRepository.Evidence> evidence,
        boolean canAuthorizeCapital) {}
}
