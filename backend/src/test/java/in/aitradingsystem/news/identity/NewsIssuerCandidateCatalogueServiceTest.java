package in.aitradingsystem.news.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.InstrumentCatalogueResearchExport;
import in.aitradingsystem.instrument.InstrumentCatalogueResearchExport.ResearchInstrument;
import in.aitradingsystem.instrument.InstrumentCatalogueResearchService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Synthetic broker instruments: not evidence of real issuers, ISINs, or news matching. */
class NewsIssuerCandidateCatalogueServiceTest {
    private static final Instant AS_OF = Instant.parse("2026-11-24T05:00:00Z");
    private static final UUID VERSION = UUID.fromString("00000000-0000-0000-0000-000000000008");

    private ResearchInstrument equity(long id, String exchange, String symbol) {
        return new ResearchInstrument(id, exchange + "_CM", exchange, "token-" + id, symbol,
            "Fixture " + symbol, "EQ", null, null, 1, BigDecimal.valueOf(0.05));
    }

    private InstrumentCatalogueResearchExport export(List<ResearchInstrument> rows, Instant availableAt) {
        return new InstrumentCatalogueResearchExport("instrument-catalogue.v1",
            AS_OF, AS_OF, availableAt, VERSION, availableAt, "https://source.example.test/master",
            rows.size(), 0, List.of("NSE", "BSE"), "EQ", rows.size(),
            "fixture-hash", "RESEARCH_CATALOGUE_ONLY", false, rows);
    }

    private NewsIssuerCandidateCatalogueService service(InstrumentCatalogueResearchService research,
                                                        InstrumentCatalogueResearchExport snapshot) {
        when(research.exportCashCatalogue(eq(AS_OF), eq(50_000))).thenReturn(snapshot);
        return new NewsIssuerCandidateCatalogueService(research);
    }

    @Test
    void readsCompletePointInTimeCatalogueWithoutInventingVerifiedIssuer() {
        var research = mock(InstrumentCatalogueResearchService.class);
        var service = service(research, export(List.of(
            equity(1, "NSE", "ALPHA-EQ"), equity(2, "BSE", "BETA")),
            AS_OF.minusSeconds(120)));
        var result = service.asOf(AS_OF);

        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.nseCount()).isEqualTo(1);
        assertThat(result.bseCount()).isEqualTo(1);
        assertThat(result.verifiedIssuerCount()).isZero();
        assertThat(result.identityStatus()).isEqualTo("CANDIDATES_ONLY_IDENTITY_UNVERIFIED");
        assertThat(result.candidates()).allSatisfy(candidate ->
            assertThat(candidate.issuerIdentityStatus()).isEqualTo(result.identityStatus()));
        assertThat(result.catalogueVersion()).isEqualTo(VERSION);
        assertThat(result.sourceAvailableAt()).isBefore(AS_OF);
        verify(research).exportCashCatalogue(AS_OF, 50_000);
    }

    @Test
    void rejectsCataloguesNotAvailableAtHistoricalDecisionTime() {
        var research = mock(InstrumentCatalogueResearchService.class);
        var service = service(research, export(List.of(equity(1, "NSE", "ALPHA-EQ")),
            AS_OF.plusSeconds(300)));
        assertThatThrownBy(() -> service.asOf(AS_OF))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("point-in-time");
    }

    @Test
    void refusesCapitalAuthorizedOrMisclassifiedDerivativeAndDuplicateRows() {
        var research = mock(InstrumentCatalogueResearchService.class);
        var derivative = new ResearchInstrument(3, "NSE_FO", "NSE", "token-3", "ALPHA27NOVFUT",
            "Synthetic future", "FUT", null, null, 75, BigDecimal.valueOf(0.05));
        var service = service(research, export(List.of(derivative), AS_OF.minusSeconds(1)));
        assertThatThrownBy(() -> service.asOf(AS_OF))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Non-equity");

        var dupResearch = mock(InstrumentCatalogueResearchService.class);
        var duplicate = equity(8, "NSE", "DUP-EQ");
        var duplicateService = service(dupResearch,
            export(List.of(duplicate, duplicate), AS_OF.minusSeconds(1)));
        assertThatThrownBy(() -> duplicateService.asOf(AS_OF))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
    }

    @Test
    void bseCashInstrumentDoesNotRequireNseEqSuffix() {
        var research = mock(InstrumentCatalogueResearchService.class);
        var service = service(research,
            export(List.of(equity(42, "BSE", "UNSUFFIXED")), AS_OF.minusSeconds(1)));
        var result = service.asOf(AS_OF);
        assertThat(result.candidates()).singleElement()
            .extracting(NewsIssuerCandidateCatalogueService.Candidate::tradingSymbol)
            .isEqualTo("UNSUFFIXED");
        assertThat(result.verifiedIssuerCount()).isZero();
    }
}
