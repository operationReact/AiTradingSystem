package in.aitradingsystem.news.identity;

import in.aitradingsystem.instrument.InstrumentCatalogueResearchExport;
import in.aitradingsystem.instrument.InstrumentCatalogueResearchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Read-only, point-in-time candidate bridge to the existing durable cash equity catalogue.
 *
 * <p>A broker cash instrument is NOT a verified issuer. Broker tokens and trading symbols
 * are mutable, and this catalogue contains no attested ISIN or issuer lineage. Consumers
 * must resolve them against dated official listing / issuer identity evidence before
 * associating an article with a verified company. No automatic trading use is authorized.
 */
@Service
public class NewsIssuerCandidateCatalogueService {
    public static final int MAX_CANDIDATES = 50_000;
    public static final String STATUS = "CANDIDATES_ONLY_IDENTITY_UNVERIFIED";
    private final InstrumentCatalogueResearchService research;

    public NewsIssuerCandidateCatalogueService(InstrumentCatalogueResearchService research) {
        this.research = research;
    }

    /**
     * One causally available complete snapshot; the existing catalogue service rejects
     * an absent history, future publication, or an export larger than MAX_CANDIDATES.
     */
    public Snapshot asOf(Instant decisionAt) {
        Objects.requireNonNull(decisionAt, "decisionAt");
        InstrumentCatalogueResearchExport export =
            research.exportCashCatalogue(decisionAt, MAX_CANDIDATES);
        if (export.canAuthorizeCapital() || !"RESEARCH_CATALOGUE_ONLY".equals(export.status())
            || export.catalogueAvailableAt().isAfter(decisionAt)
            || export.instruments().isEmpty() || export.rowCount() != export.instruments().size()) {
            throw new IllegalStateException("Unverified or inconsistent point-in-time cash catalogue");
        }
        List<Candidate> rows = new ArrayList<>(export.rowCount());
        Set<String> identities = new HashSet<>();
        int nse = 0;
        int bse = 0;
        for (InstrumentCatalogueResearchExport.ResearchInstrument item : export.instruments()) {
            if (!validCashItem(item)) {
                throw new IllegalStateException("Non-equity or invalid listing in cash catalogue snapshot");
            }
            String identity = item.exchange() + ":" + item.instrumentId();
            if (!identities.add(identity)) {
                throw new IllegalStateException("Duplicate exchange/instrument identity in candidate catalogue");
            }
            if ("NSE".equals(item.exchange())) nse++; else bse++;
            rows.add(new Candidate(item.instrumentId(), item.exchange(), item.exchangeSegment(),
                item.tradingSymbol(), item.name(), item.symbolToken(), STATUS));
        }
        return new Snapshot(export.catalogueVersion(), export.catalogueAvailableAt(),
            export.catalogueFingerprint(), export.sourceUrl(), decisionAt,
            rows.size(), nse, bse, 0, STATUS, List.copyOf(rows));
    }

    private static boolean validCashItem(InstrumentCatalogueResearchExport.ResearchInstrument item) {
        if (item == null || item.instrumentId() < 1
            || item.tradingSymbol() == null || item.tradingSymbol().isBlank()
            || item.symbolToken() == null || item.symbolToken().isBlank()
            || item.expiry() != null || item.strike() != null
            || !("EQ".equalsIgnoreCase(item.instrumentType()))) return false;
        return ("NSE".equals(item.exchange()) && "NSE_CM".equals(item.exchangeSegment())
                && item.tradingSymbol().endsWith("-EQ"))
            || ("BSE".equals(item.exchange()) && "BSE_CM".equals(item.exchangeSegment()));
    }

    public record Candidate(long instrumentId, String exchange, String exchangeSegment,
                            String tradingSymbol, String name, String brokerToken,
                            String issuerIdentityStatus) {}

    public record Snapshot(UUID catalogueVersion, Instant sourceAvailableAt, String sourceFingerprint,
                           String sourceUrl, Instant requestedAsOf, int candidateCount, int nseCount,
                           int bseCount, int verifiedIssuerCount, String identityStatus,
                           List<Candidate> candidates) {}
}
