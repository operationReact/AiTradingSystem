package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import in.aitradingsystem.news.identity.NewsIssuerCandidateCatalogueService;
import in.aitradingsystem.news.identity.NewsIssuerCandidateCatalogueService.Candidate;
import in.aitradingsystem.news.identity.NewsIssuerCandidateCatalogueService.Snapshot;
import in.aitradingsystem.news.feature.NewsInstrumentVerifiedEvidenceService.ResearchResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoricalNewsCoverageServiceTest {
    private final Instant cutoff = Instant.now().minusSeconds(60);
    private final UUID version = UUID.randomUUID();
    private final UUID listing = UUID.randomUUID();
    private final String fingerprint = "a".repeat(64);
    private final NewsIssuerCandidateCatalogueService catalogue = mock(NewsIssuerCandidateCatalogueService.class);
    private final NewsInstrumentVerifiedEvidenceService evidence = mock(NewsInstrumentVerifiedEvidenceService.class);
    private final HistoricalNewsCoverageService service = new HistoricalNewsCoverageService(catalogue, evidence);

    private void snapshot() {
        when(catalogue.asOf(cutoff)).thenReturn(new Snapshot(version, cutoff.minusSeconds(60),
            fingerprint, "https://example.test/catalogue", cutoff, 3, 2, 1, 0,
            NewsIssuerCandidateCatalogueService.STATUS, List.of(
                new Candidate(1, "NSE", "NSE_CM", "ALPHA-EQ", "Alpha", "1", "fixture"),
                new Candidate(2, "BSE", "BSE_CM", "BETA", "Beta", "2", "fixture"),
                new Candidate(3, "NSE", "NSE_CM", "GAMMA-EQ", "Gamma", "3", "fixture"))));
    }

    @Test
    void distinguishesMeasuredPageFromEntireUniverseAndMissingEvidenceFromIdentityFailure() {
        snapshot();
        when(evidence.verifiedForInstrument(1, cutoff)).thenReturn(new ResearchResult(
            1, cutoff, version, "VERIFIED_LISTING_NO_EVENT_EVIDENCE", listing, 0, List.of(), false));
        when(evidence.verifiedForInstrument(2, cutoff)).thenReturn(new ResearchResult(
            2, cutoff, version, "LISTING_IDENTITY_UNVERIFIED", null, 0, List.of(), false));
        when(evidence.verifiedForInstrument(3, cutoff)).thenReturn(new ResearchResult(
            3, cutoff, version, "INSTRUMENT_NOT_IN_ASOF_CATALOGUE", null, 0, List.of(), false));

        var first = service.measure(cutoff, 0, 2, null, null);
        assertThat(first.totalCatalogueInstruments()).isEqualTo(3);
        assertThat(first.measuredInstruments()).isEqualTo(2);
        assertThat(first.verifiedWithoutEvidence()).isEqualTo(1);
        assertThat(first.unverifiedIdentity()).isEqualTo(1);
        assertThat(first.verifiedWithEvidence()).isZero();
        assertThat(first.nextOffset()).isEqualTo(2);
        assertThat(first.completeUniverseInThisPage()).isFalse();
        assertThat(first.canAuthorizeCapital()).isFalse();

        var last = service.measure(cutoff, 2, 2, version, fingerprint);
        assertThat(last.unavailable()).isEqualTo(1);
        assertThat(last.nextOffset()).isNull();
        assertThat(last.completeUniverseInThisPage()).isFalse();

        var all = service.measure(cutoff, 0, 3, version, fingerprint);
        assertThat(all.completeUniverseInThisPage()).isTrue();
        assertThat(all.measuredInstruments()).isEqualTo(3);
    }

    @Test
    void refusesUnpinnedContinuationAndChangedSnapshotBeforeEvidenceLookup() {
        snapshot();
        assertThatThrownBy(() -> service.measure(cutoff, 1, 1, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.measure(cutoff, 1, 1, UUID.randomUUID(), fingerprint))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.measure(cutoff, 0, 101, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(evidence);
    }

    @Test
    void rejectsContradictoryEvidenceAndCapitalAuthorization() {
        snapshot();
        when(evidence.verifiedForInstrument(1, cutoff)).thenReturn(new ResearchResult(
            1, cutoff, version, "VERIFIED_LISTING_EVENT_EVIDENCE", listing, 0, List.of(), false));
        assertThatThrownBy(() -> service.measure(cutoff, 0, 1, null, null))
            .isInstanceOf(IllegalStateException.class);
        when(evidence.verifiedForInstrument(1, cutoff)).thenReturn(new ResearchResult(
            1, cutoff, version, "VERIFIED_LISTING_NO_EVENT_EVIDENCE", listing, 0, List.of(), true));
        assertThatThrownBy(() -> service.measure(cutoff, 0, 1, null, null))
            .isInstanceOf(IllegalStateException.class);
    }
}
