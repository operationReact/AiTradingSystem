package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.config.AngelOneProperties;
import in.aitradingsystem.instrument.GoldenListingIdentityRepository;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentCatalogueResearchRepository;
import in.aitradingsystem.instrument.InstrumentCatalogueResearchRepository.CatalogueResearchVersion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Fictional evidence only. Tests never authorize orders or infer real issuers. */
class NewsInstrumentVerifiedEvidenceServiceTest {
    private static final Instant AT = Instant.parse("2026-11-24T08:45:00Z");
    private static final UUID VERSION = UUID.fromString("00000000-0000-0000-0000-000000000091");
    private static final UUID LISTING = UUID.fromString("00000000-0000-0000-0000-000000000092");
    private static final String URL =
        "https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json";
    private static final long INSTRUMENT = 91001L;

    private InstrumentCatalogueResearchRepository catalogue;
    private GoldenListingIdentityRepository listings;
    private NewsVerifiedListingEventEvidenceRepository events;
    private AngelOneProperties config;
    private NewsInstrumentVerifiedEvidenceService service;

    private Instrument equity(UUID catalogueVersion) {
        return new Instrument(INSTRUMENT, "NSE_CM", "NSE", "TOKEN-91001",
            "FICTIONAL-EQ", "Fictional issuer", "EQ", null, null, 1,
            new BigDecimal("0.05"), catalogueVersion);
    }

    @BeforeEach
    void setup() {
        catalogue = mock(InstrumentCatalogueResearchRepository.class);
        listings = mock(GoldenListingIdentityRepository.class);
        events = mock(NewsVerifiedListingEventEvidenceRepository.class);
        config = mock(AngelOneProperties.class);
        when(config.instrumentMasterUrl()).thenReturn(URL);
        when(catalogue.requireVersionAt(AT))
            .thenReturn(new CatalogueResearchVersion(VERSION, AT.minusSeconds(90),
                URL, 1, 0));
        when(catalogue.findHistoricalCashInstrument(INSTRUMENT))
            .thenReturn(equity(VERSION));
        service = new NewsInstrumentVerifiedEvidenceService(
            catalogue, listings, events, config);
    }

    @Test
    void brokerTokenAndExactDatedSymbolNeedIndependentlyVerifiedListing() {
        when(listings.resolveExactSymbolAsOf(
            "ANGEL_ONE", "NSE", "TOKEN-91001", "FICTIONAL-EQ", AT))
            .thenReturn(Optional.of(LISTING));
        var event = new NewsVerifiedListingEventEvidenceRepository.Evidence(
            UUID.randomUUID(), 1, LISTING, UUID.randomUUID(),
            "fictional-source", "FICTIONAL SOURCE",
            "https://news.example.test/issuer/1", "Fictional issuer filing",
            AT.minusSeconds(500), AT.minusSeconds(450), AT.minusSeconds(400),
            AT.minusSeconds(250), "a".repeat(64), "OTHER", "UNCLASSIFIED", false);
        when(events.verifiedAsOf(LISTING, AT)).thenReturn(List.of(event));

        var result = service.verifiedForInstrument(INSTRUMENT, AT);
        assertThat(result.verificationStatus()).isEqualTo("VERIFIED_LISTING_EVENT_EVIDENCE");
        assertThat(result.verifiedListingId()).isEqualTo(LISTING);
        assertThat(result.verifiedEventReceipts()).isEqualTo(1);
        assertThat(result.evidence()).containsExactly(event);
        assertThat(result.canAuthorizeCapital()).isFalse();
        verify(listings).resolveExactSymbolAsOf(
            "ANGEL_ONE", "NSE", "TOKEN-91001", "FICTIONAL-EQ", AT);
    }

    @Test
    void noAttestedAliasNeverCallsNewsReaderOrInventsAnIsin() {
        when(listings.resolveExactSymbolAsOf(
            "ANGEL_ONE", "NSE", "TOKEN-91001", "FICTIONAL-EQ", AT))
            .thenReturn(Optional.empty());
        var result = service.verifiedForInstrument(INSTRUMENT, AT);
        assertThat(result.verificationStatus()).isEqualTo("LISTING_IDENTITY_UNVERIFIED");
        assertThat(result.verifiedListingId()).isNull();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.canAuthorizeCapital()).isFalse();
        verify(events, never()).verifiedAsOf(LISTING, AT);
    }

    @Test
    void differentCataloguePublicationAndWrongProviderSourceBothFailClosed() {
        when(catalogue.findHistoricalCashInstrument(INSTRUMENT))
            .thenReturn(equity(UUID.randomUUID()));
        var mismatch = service.verifiedForInstrument(INSTRUMENT, AT);
        assertThat(mismatch.verificationStatus())
            .isEqualTo("INSTRUMENT_NOT_IN_ASOF_CATALOGUE");
        assertThat(mismatch.verifiedEventReceipts()).isZero();
        verify(events, never()).verifiedAsOf(LISTING, AT);

        when(catalogue.requireVersionAt(AT))
            .thenReturn(new CatalogueResearchVersion(
                VERSION, AT.minusSeconds(90), "https://unrelated.example.test/data", 1, 0));
        var foreign = service.verifiedForInstrument(INSTRUMENT, AT);
        assertThat(foreign.verificationStatus()).isEqualTo("UNVERIFIED_CATALOGUE_SOURCE");
    }

    @Test
    void derivativesAndMissingHistoryNeverProduceNewsImpactEvidence() {
        when(catalogue.findHistoricalCashInstrument(INSTRUMENT))
            .thenReturn(new Instrument(INSTRUMENT, "NSE_FO", "NSE", "TOKEN-91001",
                "FICTIONAL25DECFUT", "Fictional future", "FUT", null,
                BigDecimal.valueOf(2500), 50, new BigDecimal("0.05"), VERSION));
        assertThat(service.verifiedForInstrument(INSTRUMENT, AT).verificationStatus())
            .isEqualTo("NOT_CASH_EQUITY");
        verify(events, never()).verifiedAsOf(LISTING, AT);

        when(catalogue.findHistoricalCashInstrument(INSTRUMENT)).thenReturn(null);
        assertThat(service.verifiedForInstrument(INSTRUMENT, AT).verificationStatus())
            .isEqualTo("INSTRUMENT_NOT_IN_ASOF_CATALOGUE");
    }

    @Test
    void ambiguousProviderAliasAndFutureCutoffAreNeverSilentlyAccepted() {
        when(listings.resolveExactSymbolAsOf(
            "ANGEL_ONE", "NSE", "TOKEN-91001", "FICTIONAL-EQ", AT))
            .thenThrow(new IllegalStateException("ambiguous historical provider listing identity"));
        assertThatThrownBy(() -> service.verifiedForInstrument(INSTRUMENT, AT))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ambiguous");
        assertThatThrownBy(() -> service.verifiedForInstrument(INSTRUMENT, Instant.now().plusSeconds(300)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifiedListingWithoutAuthorizedEventsIsNotAnAllClearSignal() {
        when(listings.resolveExactSymbolAsOf(
            "ANGEL_ONE", "NSE", "TOKEN-91001", "FICTIONAL-EQ", AT))
            .thenReturn(Optional.of(LISTING));
        when(events.verifiedAsOf(LISTING, AT)).thenReturn(List.of());

        var result = service.verifiedForInstrument(INSTRUMENT, AT);
        assertThat(result.verificationStatus())
            .isEqualTo("VERIFIED_LISTING_NO_EVENT_EVIDENCE");
        assertThat(result.verifiedEventReceipts()).isZero();
        assertThat(result.canAuthorizeCapital()).isFalse();
    }
}
