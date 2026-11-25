package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * D20-05A strict, read-only, single-listing daily OHLCV quality gate.
 *
 * Accepts only the explicitly documented pilot envelope (not arbitrary vendor
 * CSV). In production a source adapter must retain and attest the vendor-native
 * bytes separately before mapping them to this envelope. Neither calendar
 * evidence nor listing identity is inferred from contemporary broker tokens.
 *
 * This class is NOT a Spring bean. No licensed source adapter, scheduler,
 * user endpoint, cloud configuration or ingestion job is enabled.
 */
public final class GoldenDailyBarValidator implements GoldenDatasetChunkValidator {
    public static final String HEADER = "listing_id,exchange,session_date,open,high,low,close,"
            + "volume,source_published_at,first_available_at,source_revision,price_basis";
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final String DIGEST = "[a-f0-9]{64}";
    private static final int MAX_DAYS = GoldenSourceBackfillCoordinator.MAX_CHUNK_DAYS;

    private final GoldenHistoricalListingEvidence listings;
    private final GoldenHistoricalSessionEvidence sessions;
    private final Clock clock;

    public GoldenDailyBarValidator(
            GoldenHistoricalListingEvidence listings,
            GoldenHistoricalSessionEvidence sessions, Clock clock) {
        this.listings = Objects.requireNonNull(listings);
        this.sessions = Objects.requireNonNull(sessions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public ValidatedChunk validate(
            GoldenHistoricalDatasetProvider.ChunkRequest request,
            GoldenHistoricalDatasetProvider.SourcePayload source) {
        Report report = inspect(request, source);
        return new ValidatedChunk(report.expectedSessions(), report.validBars(),
                report.firstAvailableAt(), report.lastAvailableAt());
    }

    /**
     * Every day in the requested interval needs *verified* exchange evidence
     * even when closed. Missing trading-day bars remain INCOMPLETE; conflicting,
     * future, adjusted, unknown or duplicate bars are rejected/quarantined.
     */
    public Report inspect(
            GoldenHistoricalDatasetProvider.ChunkRequest request,
            GoldenHistoricalDatasetProvider.SourcePayload source) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(source, "source");
        Instant now = clock.instant();
        if (request.listingId() == null || request.fromDate() == null
                || request.throughDate() == null
                || !"ONE_DAY".equals(request.intervalName())
                || request.fromDate().isAfter(request.throughDate())
                || ChronoUnit.DAYS.between(request.fromDate(), request.throughDate()) >= MAX_DAYS
                || request.maxBytes() <= 0
                || source.bytes().length > Math.min(request.maxBytes(),
                    GoldenSourceBackfillCoordinator.MAX_BYTES)
                || source.sourceEvidenceDigest() == null
                || !source.sourceEvidenceDigest().matches(DIGEST)) {
            throw new IllegalArgumentException("invalid bounded daily source contract");
        }
        var listing = Objects.requireNonNull(listings.resolve(request.listingId()),
                "verified listing evidence required");
        if (!request.listingId().equals(listing.listingId())
                || !("NSE".equals(listing.exchange()) || "BSE".equals(listing.exchange()))
                || listing.listedFrom() == null
                || listing.identityEvidenceDigest() == null
                || !listing.identityEvidenceDigest().matches(DIGEST)
                || request.fromDate().isBefore(listing.listedFrom())
                || (listing.delistedOn() != null
                    && !request.throughDate().isBefore(listing.delistedOn()))) {
            throw new IllegalArgumentException("unverified historical listing episode");
        }

        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source.bytes())).toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("daily payload must use strict UTF-8", ex);
        }
        if (text.startsWith("\uFEFF") || text.contains("\r") || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid daily payload encoding");
        }
        String[] lines = text.split("\n", -1);
        int end = lines.length;
        if (end > 0 && lines[end - 1].isEmpty()) end--;
        if (end < 1 || !HEADER.equals(lines[0]) || end - 1 > MAX_DAYS) {
            throw new IllegalArgumentException("incorrect daily schema or oversized sample");
        }

        Set<LocalDate> expected = new HashSet<>();
        java.util.Map<LocalDate, Instant> closeAt = new java.util.HashMap<>();
        for (LocalDate day = request.fromDate(); !day.isAfter(request.throughDate());
             day = day.plusDays(1)) {
            var evidence = Objects.requireNonNull(sessions.resolve(listing.exchange(), day),
                    "historical session evidence required");
            if (evidence.status() == null
                    || evidence.status() == GoldenHistoricalSessionEvidence.Status.UNKNOWN
                    || evidence.evidenceDigest() == null
                    || !evidence.evidenceDigest().matches(DIGEST)
                    || evidence.verifiedAt() == null || evidence.verifiedAt().isAfter(now)) {
                throw new IllegalStateException("unverified historical market session");
            }
            if (evidence.status() == GoldenHistoricalSessionEvidence.Status.TRADING) {
                Instant close = evidence.sessionClose();
                if (close == null || close.isAfter(now)
                        || !close.atZone(KOLKATA).toLocalDate().equals(day)) {
                    throw new IllegalStateException("invalid historical trading session closure");
                }
                expected.add(day);
                closeAt.put(day, close);
            } else if (evidence.sessionClose() != null) {
                throw new IllegalStateException("closed-session proof conflicts with close time");
            }
        }

        Set<LocalDate> actual = new HashSet<>();
        Instant earliest = null;
        Instant latest = null;
        LocalDate previous = null;
        for (int i = 1; i < end; i++) {
            String[] cols = lines[i].split(",", -1);
            if (cols.length != 12) throw new IllegalArgumentException("invalid daily row field count");
            if (!request.listingId().toString().equals(cols[0])
                    || !listing.exchange().equals(cols[1]) || !cols[11].equals("RAW")) {
                throw new IllegalArgumentException("daily row has wrong listing, exchange or price basis");
            }
            LocalDate date;
            Instant published;
            Instant available;
            try {
                date = LocalDate.parse(cols[2]);
                published = Instant.parse(cols[8]);
                available = Instant.parse(cols[9]);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("malformed daily date or point-in-time timestamp", ex);
            }
            if (date.isBefore(request.fromDate()) || date.isAfter(request.throughDate())
                    || (previous != null && !previous.isBefore(date))
                    || !expected.contains(date) || !actual.add(date)) {
                throw new IllegalArgumentException("duplicate, out-of-order or closed-session candle");
            }
            previous = date;
            Instant close = closeAt.get(date);
            if (published.isBefore(close) || available.isBefore(published)
                    || available.isAfter(now)
                    || !cols[10].matches("[1-9][0-9]{0,8}")) {
                throw new IllegalArgumentException("unavailable, future or unversioned daily candle");
            }
            BigDecimal open = decimal(cols[3]);
            BigDecimal high = decimal(cols[4]);
            BigDecimal low = decimal(cols[5]);
            BigDecimal last = decimal(cols[6]);
            long volume;
            try {
                if (!cols[7].matches("[1-9][0-9]{0,18}"))
                    throw new NumberFormatException("nonpositive volume");
                volume = Long.parseLong(cols[7]);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("daily traded volume must be positive integer", ex);
            }
            if (volume <= 0 || high.compareTo(low) < 0
                    || high.compareTo(open) < 0 || high.compareTo(last) < 0
                    || low.compareTo(open) > 0 || low.compareTo(last) > 0) {
                throw new IllegalArgumentException("inconsistent daily OHLCV");
            }
            if (earliest == null || available.isBefore(earliest)) earliest = available;
            if (latest == null || available.isAfter(latest)) latest = available;
        }
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate day = request.fromDate(); !day.isAfter(request.throughDate());
             day = day.plusDays(1)) {
            if (expected.contains(day) && !actual.contains(day)) missing.add(day);
        }
        return new Report(expected.size(), actual.size(), List.copyOf(missing),
                earliest, latest);
    }

    private static BigDecimal decimal(String text) {
        try {
            if (!text.matches("(?:0|[1-9][0-9]{0,16})(?:\\.[0-9]{1,10})?"))
                throw new NumberFormatException("invalid precision");
            BigDecimal value = new BigDecimal(text);
            if (value.signum() <= 0 || value.precision() > 28)
                throw new NumberFormatException("nonpositive or oversized");
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid positive daily price", ex);
        }
    }

    public record Report(long expectedSessions, long validBars,
                         List<LocalDate> missingSessions,
                         Instant firstAvailableAt, Instant lastAvailableAt) {
        public boolean complete() {
            return expectedSessions > 0 && expectedSessions == validBars
                    && missingSessions.isEmpty();
        }
    }
}
