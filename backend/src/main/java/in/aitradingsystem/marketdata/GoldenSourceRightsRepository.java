package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * D20-03A guarded Golden Source use-eligibility lookup.
 *
 * This only consumes pre-verified rights evidence from the private registry.
 * It cannot verify legal agreements or their digital signatures and is not
 * an API for granting permissions. With an empty registry it denies all use.
 * The usage instant is WHEN MintTick consumes the dataset, not the date of a
 * retrospective stock-market decision in a historical backtest.
 */
@Repository
public class GoldenSourceRightsRepository {
    public enum Use {
        RAW_RETENTION,
        MODEL_TRAINING,
        MODEL_INFERENCE,
        END_USER_DISPLAY,
        REDISTRIBUTION
    }

    private final JdbcTemplate jdbc;

    public GoldenSourceRightsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isPermitted(
            String providerCode, String sourceDomain, Use use, Instant usageAt) {
        if (providerCode == null || !providerCode.matches("[A-Z0-9_]{3,80}")
                || sourceDomain == null
                || !sourceDomain.matches("[a-z][a-z0-9_]{2,79}")
                || use == null || usageAt == null) {
            throw new IllegalArgumentException("valid provider, source, use and usage time required");
        }
        var at = OffsetDateTime.ofInstant(usageAt, ZoneOffset.UTC);
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM golden_source_rights_receipt r
                  JOIN golden_data_provider p ON p.provider_id = r.provider_id
                 WHERE p.provider_code = ?
                   AND p.source_kind = 'EXTERNAL'
                   AND r.source_domain = ?
                   AND r.permitted_use = ?
                   AND r.receipt_status = 'VERIFIED'
                   AND r.verified_at IS NOT NULL
                   AND r.verified_by IS NOT NULL
                   AND r.revoked_at IS NULL
                   AND r.captured_at <= ?
                   AND r.verified_at <= ?
                   AND r.valid_from <= ?
                   AND (r.valid_to IS NULL OR r.valid_to > ?)
                """, Long.class, providerCode, sourceDomain, use.name(),
                at, at, at, at);
        return Objects.requireNonNullElse(count, 0L) > 0;
    }

    public boolean permitsRetentionAndTraining(
            String providerCode, String sourceDomain, Instant usageAt) {
        return isPermitted(providerCode, sourceDomain, Use.RAW_RETENTION, usageAt)
                && isPermitted(providerCode, sourceDomain, Use.MODEL_TRAINING, usageAt);
    }
}
