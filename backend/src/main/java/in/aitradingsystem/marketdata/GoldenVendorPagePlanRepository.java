package in.aitradingsystem.marketdata;

import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D20-05E metadata-only page scheduling, separate from 31-day source quality
 * chunks. It has NO HTTP client, provider credentials, raw-byte storage,
 * source-authorized connector, automatic scheduling, or customer endpoint.
 *
 * An approved future worker must recheck V78 rights immediately before any
 * provider API request, and archive vendor-native data with physical proof.
 */
@Repository
public class GoldenVendorPagePlanRepository {
    private static final String DOMAIN = "daily_bars";
    private static final int VENDOR_DAYS = GoldenDailyAcquisitionPartitioner.MAX_VENDOR_WINDOW_DAYS;
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LEASE_LENGTH = Duration.ofMinutes(5);
    private static final Duration MAX_HISTORY = Duration.ofDays(25L * 366);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final GoldenSourceRightsRepository rights;
    private final Clock clock;

    @Autowired
    public GoldenVendorPagePlanRepository(
            JdbcTemplate jdbc, TransactionTemplate tx, GoldenSourceRightsRepository rights) {
        this(jdbc, tx, rights, Clock.systemUTC());
    }

    GoldenVendorPagePlanRepository(
            JdbcTemplate jdbc, TransactionTemplate tx,
            GoldenSourceRightsRepository rights, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
        this.rights = Objects.requireNonNull(rights);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Idempotent metadata planning only: creates zero real vendor requests.
     * Requires a registered external provider and an existing stable V77
     * listing episode, not a current broker token or live instruments row.
     */
    public Plan plan(String providerCode, UUID listingId, LocalDate from,
                     LocalDate through, int datasetRevision) {
        checkProviderCode(providerCode);
        if (listingId == null || from == null || through == null
                || from.isAfter(through)
                || ChronoUnit.DAYS.between(from, through) > MAX_HISTORY.toDays()
                || datasetRevision < 1) {
            throw new IllegalArgumentException("valid historical listing episode and revision required");
        }
        UUID providerId = requireProvider(providerCode);
        Long listings = jdbc.queryForObject("""
                SELECT count(*) FROM golden_listing_identity
                 WHERE listing_id = ? AND listed_from <= ?
                   AND (delisted_on IS NULL OR delisted_on > ?)
                """, Long.class, listingId, Date.valueOf(from), Date.valueOf(through));
        if (!Long.valueOf(1).equals(listings)) {
            throw new IllegalArgumentException("historical listing episode not independently registered");
        }
        return Objects.requireNonNull(tx.execute(ignored -> {
            jdbc.update("""
                    INSERT INTO golden_vendor_page_provider_gate (provider_id)
                    VALUES (?) ON CONFLICT DO NOTHING
                    """, providerId);
            int count = 0;
            for (LocalDate start = from; !start.isAfter(through); ) {
                LocalDate stop = start.plusDays(VENDOR_DAYS - 1L);
                if (stop.isAfter(through)) stop = through;
                jdbc.update("""
                        INSERT INTO golden_vendor_page_plan
                            (page_id, provider_id, listing_id, source_domain, interval_name,
                             from_date, through_date, dataset_revision)
                        VALUES (?, ?, ?, ?, 'ONE_DAY', ?, ?, ?)
                        ON CONFLICT ON CONSTRAINT uq_golden_vendor_page_window DO NOTHING
                        """, UUID.randomUUID(), providerId, listingId, DOMAIN,
                        Date.valueOf(start), Date.valueOf(stop), datasetRevision);
                count++;
                start = stop.plusDays(1);
            }
            return new Plan(listingId, from, through, count, datasetRevision);
        }));
    }

    /**
     * Claims AT MOST ONE 2000-day page with an atomic provider-wide gate.
     * Empty/no rights => no lease, and critically no eventual external call.
     * Leases do not imply source access or successful provider acquisition.
     */
    public Optional<PageLease> claim(String providerCode) {
        checkProviderCode(providerCode);
        Instant now = clock.instant();
        if (!hasRetentionRights(providerCode, now)) return Optional.empty();
        return Objects.requireNonNull(tx.execute(ignored -> {
            // A future external caller MUST recheck rights when it performs I/O.
            if (!hasRetentionRights(providerCode, clock.instant())) return Optional.empty();
            UUID providerId = requireProvider(providerCode);
            List<Gate> gates = jdbc.query("""
                    SELECT next_allowed_at, min_spacing_seconds
                      FROM golden_vendor_page_provider_gate
                     WHERE provider_id = ? FOR UPDATE
                    """, (rs, i) -> new Gate(
                    rs.getObject("next_allowed_at", OffsetDateTime.class).toInstant(),
                    rs.getInt("min_spacing_seconds")), providerId);
            if (gates.size() != 1 || now.isBefore(gates.getFirst().nextAllowedAt())) {
                return Optional.empty();
            }
            jdbc.update("""
                    UPDATE golden_vendor_page_plan
                       SET status = 'FAILED', lease_token = NULL, lease_until = NULL,
                           updated_at = ?
                     WHERE provider_id = ? AND status = 'LEASED'
                       AND lease_until <= ? AND attempts >= ?
                    """, utc(now), providerId, utc(now), MAX_ATTEMPTS);
            List<Candidate> candidates = jdbc.query("""
                    SELECT page_id, listing_id, from_date, through_date, attempts
                      FROM golden_vendor_page_plan
                     WHERE provider_id = ? AND source_domain = ?
                       AND attempts < ? AND next_attempt_at <= ?
                       AND (status IN ('PENDING','RETRY')
                            OR (status = 'LEASED' AND lease_until <= ?))
                     ORDER BY from_date, page_id
                     FOR UPDATE SKIP LOCKED LIMIT 1
                    """, (rs, i) -> new Candidate(
                    rs.getObject("page_id", UUID.class),
                    rs.getObject("listing_id", UUID.class),
                    rs.getDate("from_date").toLocalDate(),
                    rs.getDate("through_date").toLocalDate()),
                    providerId, DOMAIN, MAX_ATTEMPTS, utc(now), utc(now));
            if (candidates.isEmpty()) return Optional.empty();
            Candidate c = candidates.getFirst();
            UUID token = UUID.randomUUID();
            Instant until = now.plus(LEASE_LENGTH);
            int count = jdbc.update("""
                    UPDATE golden_vendor_page_plan
                       SET status = 'LEASED', attempts = attempts + 1,
                           lease_token = ?, lease_until = ?, updated_at = ?
                     WHERE page_id = ? AND attempts < ?
                    """, token, utc(until), utc(now), c.pageId(), MAX_ATTEMPTS);
            if (count != 1) return Optional.empty();
            jdbc.update("""
                    UPDATE golden_vendor_page_provider_gate
                       SET next_allowed_at = ?, updated_at = ?
                     WHERE provider_id = ?
                    """, utc(now.plusSeconds(gates.getFirst().minSpacingSeconds())),
                    utc(now), providerId);
            return Optional.of(new PageLease(c.pageId(), c.listingId(),
                    c.from(), c.through(), token, until));
        }));
    }

    /**
     * One transient provider failure creates a durable provider-wide cooldown.
     * Fenced stale workers cannot change a newly re-leased page.
     */
    public boolean retry(PageLease lease) {
        if (lease == null) throw new IllegalArgumentException("lease required");
        Instant now = clock.instant();
        return Boolean.TRUE.equals(tx.execute(ignored -> {
            List<Failure> affected = jdbc.query("""
                    SELECT provider_id, attempts
                      FROM golden_vendor_page_plan
                     WHERE page_id = ? AND status = 'LEASED'
                       AND lease_token = ? AND lease_until > ?
                     FOR UPDATE
                    """, (rs, i) -> new Failure(
                    rs.getObject("provider_id", UUID.class),
                    rs.getInt("attempts")),
                    lease.pageId(), lease.token(), utc(now));
            if (affected.size() != 1) return false;
            Failure f = affected.getFirst();
            long seconds = Math.min(1_800L, 30L << Math.max(0, f.attempts() - 1));
            int count = jdbc.update("""
                    UPDATE golden_vendor_page_plan
                       SET status = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'RETRY' END,
                           lease_token = NULL, lease_until = NULL,
                           next_attempt_at = ?, updated_at = ?
                     WHERE page_id = ? AND status = 'LEASED' AND lease_token = ?
                       AND lease_until > ?
                    """, MAX_ATTEMPTS, utc(now.plusSeconds(seconds)), utc(now),
                    lease.pageId(), lease.token(), utc(now));
            if (count != 1) return false;
            jdbc.update("""
                    UPDATE golden_vendor_page_provider_gate
                       SET next_allowed_at = GREATEST(next_allowed_at, ?),
                           updated_at = ?
                     WHERE provider_id = ?
                    """, utc(now.plusSeconds(seconds)), utc(now), f.providerId());
            return true;
        }));
    }

    /** Metadata counts only. A 'STAGED' row is not by itself verified physical custody. */
    public Progress progress(String providerCode, UUID listingId, int revision) {
        checkProviderCode(providerCode);
        if (listingId == null || revision < 1)
            throw new IllegalArgumentException("listing and revision required");
        return jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE v.status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE v.status = 'LEASED') AS leased,
                       count(*) FILTER (WHERE v.status = 'RETRY') AS retrying,
                       count(*) FILTER (WHERE v.status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE v.status = 'STAGED') AS staged
                  FROM golden_vendor_page_plan v
                  JOIN golden_data_provider p ON p.provider_id = v.provider_id
                 WHERE p.provider_code = ? AND v.listing_id = ?
                   AND v.source_domain = ? AND v.dataset_revision = ?
                """, (rs, i) -> new Progress(
                rs.getLong("total"), rs.getLong("pending"), rs.getLong("leased"),
                rs.getLong("retrying"), rs.getLong("failed"), rs.getLong("staged")),
                providerCode, listingId, DOMAIN, revision);
    }

    private boolean hasRetentionRights(String providerCode, Instant now) {
        return rights.isPermitted(providerCode, DOMAIN,
                GoldenSourceRightsRepository.Use.RAW_RETENTION, now);
    }

    private UUID requireProvider(String code) {
        List<UUID> ids = jdbc.query("""
                SELECT provider_id FROM golden_data_provider
                 WHERE provider_code = ? AND source_kind = 'EXTERNAL'
                """, (rs, i) -> rs.getObject("provider_id", UUID.class), code);
        if (ids.size() != 1)
            throw new IllegalArgumentException("registered external source required");
        return ids.getFirst();
    }

    private static void checkProviderCode(String code) {
        if (code == null || !code.matches("[A-Z0-9_]{3,80}"))
            throw new IllegalArgumentException("registered provider code required");
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private record Gate(Instant nextAllowedAt, int minSpacingSeconds) {}
    private record Candidate(UUID pageId, UUID listingId,
                             LocalDate from, LocalDate through) {}
    private record Failure(UUID providerId, int attempts) {}

    public record Plan(UUID listingId, LocalDate from, LocalDate through,
                       int pageCount, int revision) {}
    public record PageLease(UUID pageId, UUID listingId, LocalDate from, LocalDate through,
                            UUID token, Instant leaseUntil) {}
    public record Progress(long pages, long pending, long leased, long retrying,
                           long failed, long staged) {}
}
