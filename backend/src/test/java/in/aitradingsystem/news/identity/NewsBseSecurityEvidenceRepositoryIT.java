package in.aitradingsystem.news.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real PostgreSQL proof of source-as-of / imported-at filtering.
 * Uses fictional BSE security master archives, not real market observations.
 */
class NewsBseSecurityEvidenceRepositoryIT extends AbstractPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired NewsBseSecurityEvidenceRepository repository;

    private void source(UUID id, LocalDate sourceAsOf, Instant imported, String hash,
                        String code, String securityId, String isin) {
        jdbc.update("""
                INSERT INTO bse_security_master_import
                    (id, source_url, source_file, source_as_of, archive_sha256,
                     total_records, verified_equities, imported_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, "https://www.bseindia.com/downloads/Help/file/scrip.zip",
            "fictional-security-master.zip", Date.valueOf(sourceAsOf), hash,
            1, 1, OffsetDateTime.ofInstant(imported, java.time.ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO bse_verified_cash_equity(import_id, scrip_code, security_id, isin)
                VALUES (?, ?, ?, ?)
                """, id, code, securityId, isin);
    }

    @Test
    void usesLatestOfficialImportActuallyKnownAtDecisionWithoutFutureIsinLeakage() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        source(firstId, LocalDate.parse("2020-02-01"),
            Instant.parse("2020-02-02T02:00:00Z"), "b".repeat(64),
            "598001", "FICTIONAL", "IN0000000001");
        source(secondId, LocalDate.parse("2020-02-03"),
            Instant.parse("2020-02-04T02:00:00Z"), "c".repeat(64),
            "598001", "FICTIONAL", "IN0000000002");

        var earlier = repository.latestAvailableAt(
            Instant.parse("2020-02-03T09:00:00Z"), LocalDate.parse("2020-02-03"));
        assertThat(earlier).hasSize(1);
        assertThat(earlier.getFirst().importId()).isEqualTo(firstId);
        assertThat(earlier.getFirst().isin()).isEqualTo("IN0000000001");
        assertThat(earlier.getFirst().firstAvailableAt())
            .isEqualTo(Instant.parse("2020-02-02T02:00:00Z"));

        var later = repository.latestAvailableAt(
            Instant.parse("2020-02-05T09:00:00Z"), LocalDate.parse("2020-02-05"));
        assertThat(later).hasSize(1);
        assertThat(later.getFirst().importId()).isEqualTo(secondId);
        assertThat(later.getFirst().isin()).isEqualTo("IN0000000002");
        assertThat(later.getFirst().sourceAsOf()).isEqualTo(LocalDate.parse("2020-02-03"));
    }

    @Test
    void historicalAsOfBeforeAnySourceImportReturnsNoProof() {
        var result = repository.latestAvailableAt(
            Instant.parse("1990-01-01T10:00:00Z"), LocalDate.parse("1990-01-01"));
        assertThat(result).isEmpty();
    }
}
