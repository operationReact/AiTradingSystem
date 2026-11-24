package in.aitradingsystem.news.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.news.identity.NewsBseSecurityEvidenceRepository.SourceSecurity;
import in.aitradingsystem.news.identity.NewsIssuerCandidateCatalogueService.Candidate;
import in.aitradingsystem.news.identity.NewsIssuerCandidateCatalogueService.Snapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NewsBseSecurityEvidenceServiceTest {
    private static final Instant DECISION = Instant.parse("2026-11-24T09:00:00Z");
    private static final UUID BROKER = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID OFFICIAL = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final String HASH = "a".repeat(64);

    private static Candidate bse(long id, String code, String symbol) {
        return new Candidate(id, "BSE", "BSE_CM", symbol, symbol + " FICTIONAL", code,
            NewsIssuerCandidateCatalogueService.STATUS);
    }

    private static Snapshot candidates(Candidate... rows) {
        return new Snapshot(BROKER, DECISION.minusSeconds(400),
            "synthetic-fingerprint", "https://source.example.test/fixtures", DECISION,
            rows.length, 0, rows.length, 0, NewsIssuerCandidateCatalogueService.STATUS, List.of(rows));
    }

    private static SourceSecurity official(String code, String symbol, String isin) {
        return new SourceSecurity(code, symbol, isin, OFFICIAL,
            LocalDate.parse("2026-11-22"), DECISION.minusSeconds(1800), HASH);
    }

    private NewsBseSecurityEvidenceService service(Snapshot snapshot, List<SourceSecurity> rows) {
        var candidateService = mock(NewsIssuerCandidateCatalogueService.class);
        var officialRepo = mock(NewsBseSecurityEvidenceRepository.class);
        when(candidateService.asOf(DECISION)).thenReturn(snapshot);
        when(officialRepo.latestAvailableAt(eq(DECISION), eq(LocalDate.parse("2026-11-24"))))
            .thenReturn(rows);
        return new NewsBseSecurityEvidenceService(candidateService, officialRepo);
    }

    @Test
    void verifiesSecurityIsinOnlyOnExactBseNumericCodeAndSecurityId() {
        var coverage = service(candidates(
            bse(11, "500001", "ACME"),
            bse(12, "500002", "BETA"),
            bse(13, "500003", "GAMMA")),
            List.of(official("500001", "ACME", "IN0000000001"),
                official("500002", "DIFFERENT", "IN0000000002")))
            .asOf(DECISION);
        assertThat(coverage.bseCandidateCount()).isEqualTo(3);
        assertThat(coverage.verifiedSecurityMatches()).isEqualTo(1);
        assertThat(coverage.unmatchedCandidates()).isEqualTo(2);
        assertThat(coverage.coverageStatus()).isEqualTo("BSE_SECURITY_MATCHES_PARTIAL");
        assertThat(coverage.matches()).singleElement().satisfies(evidence -> {
            assertThat(evidence.brokerInstrumentId()).isEqualTo(11);
            assertThat(evidence.verifiedIsin()).isEqualTo("IN0000000001");
            assertThat(evidence.sourceHash()).isEqualTo(HASH);
            assertThat(evidence.bseSourceAsOf()).isEqualTo(LocalDate.parse("2026-11-22"));
            assertThat(evidence.evidenceStatus()).isEqualTo("OFFICIAL_BSE_SECURITY_ISIN");
        });
    }

    @Test
    void noSourceProducesUnknownRatherThanInventingAnIsin() {
        var result = service(candidates(bse(8, "500010", "ABC")), List.of()).asOf(DECISION);
        assertThat(result.coverageStatus()).isEqualTo("OFFICIAL_SOURCE_UNAVAILABLE");
        assertThat(result.verifiedSecurityMatches()).isZero();
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void oldSourceMustNeverValidateCurrentSecurity() {
        var stale = new SourceSecurity("500001", "ACME", "IN0000000001", OFFICIAL,
            LocalDate.parse("2026-10-01"), DECISION.minusSeconds(1800), HASH);
        var result = service(candidates(bse(11, "500001", "ACME")), List.of(stale))
            .asOf(DECISION);
        assertThat(result.coverageStatus()).isEqualTo("OFFICIAL_SOURCE_STALE");
        assertThat(result.verifiedSecurityMatches()).isZero();
    }

    @Test
    void futureAvailableAndMixedSourceRevisionsFailClosed() {
        var future = new SourceSecurity("500001", "ACME", "IN0000000001", OFFICIAL,
            LocalDate.parse("2026-11-22"), DECISION.plusSeconds(1), HASH);
        assertThatThrownBy(() ->
            service(candidates(bse(11, "500001", "ACME")), List.of(future)).asOf(DECISION))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("point-in-time");

        var second = new SourceSecurity("500002", "BETA", "IN0000000002", UUID.randomUUID(),
            LocalDate.parse("2026-11-22"), DECISION.minusSeconds(1800), HASH);
        assertThatThrownBy(() -> service(candidates(bse(11, "500001", "ACME")),
            List.of(official("500001", "ACME", "IN0000000001"), second)).asOf(DECISION))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Mixed");
    }

    @Test
    void duplicateOfficialScripCodeNeverMatchesByGuessing() {
        var result = service(candidates(bse(11, "500001", "ACME")),
            List.of(official("500001", "ACME", "IN0000000001"),
                official("500001", "ACME", "IN0000000002"))).asOf(DECISION);
        assertThat(result.verifiedSecurityMatches()).isZero();
        assertThat(result.unmatchedCandidates()).isEqualTo(1);
    }
}
