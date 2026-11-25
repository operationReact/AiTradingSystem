package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * D20-05D: purely in-memory bridge from ONE licensed 2,000-day provider read to
 * <=31-day source-independent quality windows.
 *
 * Important: a caller must separately prove retention/training rights and
 * archive the original provider-native object BEFORE publishing normalized
 * data. This is NOT a Spring bean, provider adapter, scheduler, persistence
 * writer, or an authority to infer publication times from today's download.
 *
 * A concrete source adapter must independently substantiate each source
 * published/first-available timestamp; never populate them with "now" or a
 * synthetic session-close instant to make historical history look point-in-time.
 */
public final class GoldenDailyAcquisitionPartitioner {
    public static final int MAX_VENDOR_WINDOW_DAYS = 2_000;
    public static final int MAX_NATIVE_BYTES = GoldenSourceBackfillCoordinator.MAX_BYTES;
    private static final int CHUNK_DAYS = GoldenSourceBackfillCoordinator.MAX_CHUNK_DAYS;
    private static final String SHA256 = "[a-f0-9]{64}";

    private final GoldenDailyBarValidator validator;

    public GoldenDailyAcquisitionPartitioner(GoldenDailyBarValidator validator) {
        this.validator = Objects.requireNonNull(validator);
    }

    /**
     * Fails the WHOLE page if any row is duplicated, out of range, unordered,
     * mixed price basis, or its raw response digest is not authentic.
     *
     * A missing trading session is NOT synthesized; each output 31-day window
     * independently reports exact missing sessions after calendar lookup.
     */
    public PagePlan partition(UUID jobId, Page page) {
        if (jobId == null || page == null || page.listingId() == null
                || !("NSE".equals(page.exchange()) || "BSE".equals(page.exchange()))
                || page.from() == null || page.through() == null
                || page.from().isAfter(page.through())
                || ChronoUnit.DAYS.between(page.from(), page.through()) >= MAX_VENDOR_WINDOW_DAYS
                || page.rows() == null || page.rows().size() > MAX_VENDOR_WINDOW_DAYS
                || page.nativeBytes() == null || page.nativeBytes().length == 0
                || page.nativeBytes().length > MAX_NATIVE_BYTES
                || page.nativeSha256() == null || !page.nativeSha256().matches(SHA256)
                || page.sourceEvidenceDigest() == null
                || !page.sourceEvidenceDigest().matches(SHA256)
                || !sha256(page.nativeBytes()).equals(page.nativeSha256())) {
            throw new IllegalArgumentException("unverified or unbounded historical vendor page");
        }
        LocalDate previous = null;
        for (Row row : page.rows()) {
            if (row == null || row.sessionDate() == null
                    || row.sessionDate().isBefore(page.from())
                    || row.sessionDate().isAfter(page.through())
                    || (previous != null && !previous.isBefore(row.sessionDate()))
                    || row.sourcePublishedAt() == null || row.firstAvailableAt() == null
                    || row.open() == null || row.high() == null || row.low() == null
                    || row.close() == null || row.volume() < 0
                    || row.sourceRevision() <= 0 || !"RAW".equals(row.priceBasis())) {
                throw new IllegalArgumentException("invalid or conflicting historical row");
            }
            previous = row.sessionDate();
        }

        List<Segment> segments = new ArrayList<>();
        int rowIndex = 0;
        long valid = 0;
        long expected = 0;
        long missing = 0;
        for (LocalDate start = page.from(); !start.isAfter(page.through()); ) {
            LocalDate stop = start.plusDays(CHUNK_DAYS - 1L);
            if (stop.isAfter(page.through())) stop = page.through();
            StringBuilder csv = new StringBuilder(GoldenDailyBarValidator.HEADER).append('\n');
            while (rowIndex < page.rows().size()) {
                Row row = page.rows().get(rowIndex);
                if (row.sessionDate().isAfter(stop)) break;
                csv.append(page.listingId()).append(',')
                        .append(page.exchange()).append(',')
                        .append(row.sessionDate()).append(',')
                        .append(row.open().toPlainString()).append(',')
                        .append(row.high().toPlainString()).append(',')
                        .append(row.low().toPlainString()).append(',')
                        .append(row.close().toPlainString()).append(',')
                        .append(row.volume()).append(',')
                        .append(row.sourcePublishedAt()).append(',')
                        .append(row.firstAvailableAt()).append(',')
                        .append(row.sourceRevision()).append(',')
                        .append(row.priceBasis()).append('\n');
                rowIndex++;
            }
            byte[] normalized = csv.toString().getBytes(StandardCharsets.UTF_8);
            if (normalized.length > MAX_NATIVE_BYTES) {
                throw new IllegalArgumentException("normalized historical chunk exceeds custody bound");
            }
            // Deterministic across retries of an identical page and job;
            // a provider correction must have a new digest/revision.
            UUID segmentId = UUID.nameUUIDFromBytes(
                    (jobId + "|" + page.listingId() + "|" + start + "|"
                            + stop + "|" + page.nativeSha256())
                            .getBytes(StandardCharsets.UTF_8));
            var request = new GoldenHistoricalDatasetProvider.ChunkRequest(
                    jobId, segmentId, page.listingId(), "ONE_DAY",
                    start, stop, MAX_NATIVE_BYTES);
            var normalizedSource = new GoldenHistoricalDatasetProvider.SourcePayload(
                    normalized, page.sourceEvidenceDigest());
            // This uses the real V77 listing and V81 session authorities injected
            // into D20-05A. UNKNOWN or missing evidence fails closed.
            var quality = validator.inspect(request, normalizedSource);
            segments.add(new Segment(request, normalizedSource, quality,
                    sha256(normalized), page.nativeSha256()));
            valid += quality.validBars();
            expected += quality.expectedSessions();
            missing += quality.missingSessions().size();
            start = stop.plusDays(1);
        }
        if (rowIndex != page.rows().size()) {
            throw new IllegalArgumentException("historical page has unassigned rows");
        }
        return new PagePlan(page.listingId(), page.nativeSha256(),
                page.sourceEvidenceDigest(), List.copyOf(segments),
                expected, valid, missing, expected > 0 && expected == valid
                        && missing == 0);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 not available", impossible);
        }
    }

    public record Page(UUID listingId, String exchange, LocalDate from, LocalDate through,
                       byte[] nativeBytes, String nativeSha256, String sourceEvidenceDigest,
                       List<Row> rows) {
        public Page {
            if (nativeBytes != null) nativeBytes = nativeBytes.clone();
            if (rows != null) rows = List.copyOf(rows);
        }
        @Override public byte[] nativeBytes() {
            return nativeBytes == null ? null : nativeBytes.clone();
        }
    }

    public record Row(LocalDate sessionDate, BigDecimal open, BigDecimal high,
                      BigDecimal low, BigDecimal close, long volume,
                      Instant sourcePublishedAt, Instant firstAvailableAt,
                      int sourceRevision, String priceBasis) {}

    public record Segment(GoldenHistoricalDatasetProvider.ChunkRequest request,
                          GoldenHistoricalDatasetProvider.SourcePayload canonicalBytes,
                          GoldenDailyBarValidator.Report report,
                          String normalizedSha256, String nativePageSha256) {}

    /** Metadata only; DOES NOT persist source or normalized bytes. */
    public record PagePlan(UUID listingId, String nativeSha256,
                           String sourceEvidenceDigest, List<Segment> segments,
                           long expectedSessions, long validatedBars,
                           long missingSessions, boolean complete) {}
}
