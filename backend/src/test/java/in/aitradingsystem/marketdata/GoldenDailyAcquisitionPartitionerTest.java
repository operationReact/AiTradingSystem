package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * No real data: these synthetic tests check the independent 2000-day source
 * window to 31-day immutable custody boundary without provider/AWS calls.
 */
class GoldenDailyAcquisitionPartitionerTest {
    private static final String EVIDENCE = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-12-20T12:00:00Z");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate START = LocalDate.of(2006, 11, 10);
    private static final LocalDate END = START.plusDays(1999);

    private final UUID listing = UUID.randomUUID();
    private final UUID job = UUID.randomUUID();

    private GoldenDailyAcquisitionPartitioner prepare(List<LocalDate> trading) {
        GoldenHistoricalListingEvidence listingAuthority = id ->
                new GoldenHistoricalListingEvidence.ListingEpisode(
                        id, "NSE", LocalDate.of(2000, 1, 1), null, EVIDENCE);
        GoldenHistoricalSessionEvidence calendar = (exchange, date) -> {
            if (!"NSE".equals(exchange)) return new GoldenHistoricalSessionEvidence.SessionEvidence(
                    GoldenHistoricalSessionEvidence.Status.UNKNOWN, null, null, null);
            Instant close = trading.contains(date) ? close(date) : null;
            return new GoldenHistoricalSessionEvidence.SessionEvidence(
                    close == null ? GoldenHistoricalSessionEvidence.Status.CLOSED
                            : GoldenHistoricalSessionEvidence.Status.TRADING,
                    close, EVIDENCE, NOW.minusSeconds(3600));
        };
        return new GoldenDailyAcquisitionPartitioner(
                new GoldenDailyBarValidator(listingAuthority, calendar,
                        Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static Instant close(LocalDate d) {
        return d.atTime(15, 30).atZone(IST).toInstant();
    }

    private static GoldenDailyAcquisitionPartitioner.Row row(LocalDate day) {
        Instant start = close(day);
        return new GoldenDailyAcquisitionPartitioner.Row(
                day, new BigDecimal("101"), new BigDecimal("110"),
                new BigDecimal("90"), new BigDecimal("100"), 1000L,
                start.plusSeconds(60), start.plusSeconds(120), 1, "RAW");
    }

    private GoldenDailyAcquisitionPartitioner.Page page(
            LocalDate from, LocalDate through,
            List<GoldenDailyAcquisitionPartitioner.Row> rows) {
        byte[] original = ("SYNTHETIC_VENDOR_RESPONSE:" + from + ":" + through)
                .getBytes(StandardCharsets.UTF_8);
        return new GoldenDailyAcquisitionPartitioner.Page(listing, "NSE",
                from, through, original, hash(original), EVIDENCE, rows);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Test
    void full2000DaySourcePageBecomes65QualityChunksWithoutExtraVendorCalls() {
        List<LocalDate> sessions = List.of(START, END);
        var processor = prepare(sessions);
        var input = page(START, END, List.of(row(START), row(END)));
        var result = processor.partition(job, input);
        assertThat(result.segments()).hasSize(65);
        assertThat(result.expectedSessions()).isEqualTo(2);
        assertThat(result.validatedBars()).isEqualTo(2);
        assertThat(result.missingSessions()).isZero();
        assertThat(result.complete()).isTrue();
        assertThat(result.segments().getFirst().request().fromDate()).isEqualTo(START);
        assertThat(result.segments().getFirst().request().throughDate()).isEqualTo(
                START.plusDays(30));
        assertThat(result.segments().getLast().request().throughDate()).isEqualTo(END);
        assertThat(result.segments().getLast().request().fromDate()).isEqualTo(
                START.plusDays(31L * 64));
        assertThat(result.nativeSha256()).isEqualTo(input.nativeSha256());
        assertThat(result.segments()).allSatisfy(s -> {
            assertThat(s.request().intervalName()).isEqualTo("ONE_DAY");
            assertThat(s.nativePageSha256()).isEqualTo(input.nativeSha256());
            assertThat(s.normalizedSha256()).isEqualTo(hash(s.canonicalBytes().bytes()));
            assertThat(s.canonicalBytes().sourceEvidenceDigest()).isEqualTo(EVIDENCE);
            assertThat(s.request().listingId()).isEqualTo(listing);
        });
    }

    @Test
    void missingTrueTradingDayIsReportedWithoutSynthesizingAnyCandle() {
        var result = prepare(List.of(START, START.plusDays(1)))
                .partition(job, page(START, START.plusDays(3), List.of(row(START))));
        assertThat(result.expectedSessions()).isEqualTo(2);
        assertThat(result.validatedBars()).isEqualTo(1);
        assertThat(result.missingSessions()).isEqualTo(1);
        assertThat(result.complete()).isFalse();
        assertThat(result.segments().getFirst().report().missingSessions())
                .containsExactly(START.plusDays(1));
    }

    @Test
    void unverifiedCalendarCannotBePromotedToComplete() {
        GoldenHistoricalSessionEvidence missing = (venue, date) ->
                new GoldenHistoricalSessionEvidence.SessionEvidence(
                        GoldenHistoricalSessionEvidence.Status.UNKNOWN, null, null, null);
        GoldenDailyBarValidator strict = new GoldenDailyBarValidator(
                id -> new GoldenHistoricalListingEvidence.ListingEpisode(
                        id, "NSE", START, null, EVIDENCE),
                missing, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> new GoldenDailyAcquisitionPartitioner(strict)
                .partition(job, page(START, START, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unverified");
    }

    @Test
    void duplicateOrOutOfOrderDailyRowsRejectTheWholePage() {
        var partitioner = prepare(List.of(START, START.plusDays(1)));
        assertThatThrownBy(() -> partitioner.partition(job,
                page(START, START.plusDays(1), List.of(row(START), row(START)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> partitioner.partition(job,
                page(START, START.plusDays(1),
                        List.of(row(START.plusDays(1)), row(START)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rowsOutsideRangeCannotBeMovedOrDuplicatedAcrossChunkBoundaries() {
        var partitioner = prepare(List.of(START, START.plusDays(31)));
        assertThatThrownBy(() -> partitioner.partition(job,
                page(START, START.plusDays(30), List.of(row(START.plusDays(31))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid or conflicting");
        var result = partitioner.partition(job,
                page(START, START.plusDays(31),
                        List.of(row(START), row(START.plusDays(31)))));
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).report().validBars()).isEqualTo(1);
        assertThat(result.segments().get(1).report().validBars()).isEqualTo(1);
    }

    @Test
    void wrongNativePayloadFingerprintAndOversizedWindowsAreRejected() {
        var partitioner = prepare(List.of(START));
        var good = page(START, END, List.of(row(START)));
        var tampered = new GoldenDailyAcquisitionPartitioner.Page(
                listing, "NSE", START, END, good.nativeBytes(),
                "b".repeat(64), EVIDENCE, List.of(row(START)));
        assertThatThrownBy(() -> partitioner.partition(job, tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vendor page");
        assertThatThrownBy(() -> partitioner.partition(job,
                page(START, START.plusDays(2000), List.of(row(START)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vendor page");
        assertThatThrownBy(() -> partitioner.partition(job, new GoldenDailyAcquisitionPartitioner.Page(
                listing, "NSE", START, START, new byte[0], hash(new byte[0]),
                EVIDENCE, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currentProviderTokenCannotReplacePermanentListingIdentity() {
        var p = page(START, START, List.of(row(START)));
        var spoof = new GoldenDailyAcquisitionPartitioner.Page(
                UUID.randomUUID(), "NSE", START, START,
                p.nativeBytes(), p.nativeSha256(), EVIDENCE, List.of(row(START)));
        GoldenHistoricalListingEvidence onlyRealListing = id ->
                id.equals(listing) ? new GoldenHistoricalListingEvidence.ListingEpisode(
                        listing, "NSE", LocalDate.of(2000, 1, 1), null, EVIDENCE) : null;
        var validator = new GoldenDailyBarValidator(onlyRealListing,
                (venue, date) -> new GoldenHistoricalSessionEvidence.SessionEvidence(
                        GoldenHistoricalSessionEvidence.Status.TRADING, close(date),
                        EVIDENCE, NOW.minusSeconds(3600)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> new GoldenDailyAcquisitionPartitioner(validator)
                .partition(job, spoof)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void adjustedPriceAndPrecloseOrFutureAvailabilityAreNotRepaired() {
        var partitioner = prepare(List.of(START));
        var source = row(START);
        var adjusted = new GoldenDailyAcquisitionPartitioner.Row(
                START, source.open(), source.high(), source.low(), source.close(),
                source.volume(), source.sourcePublishedAt(), source.firstAvailableAt(),
                1, "ADJUSTED");
        assertThatThrownBy(() -> partitioner.partition(job,
                page(START, START, List.of(adjusted))))
                .isInstanceOf(IllegalArgumentException.class);
        var retroactive = new GoldenDailyAcquisitionPartitioner.Row(
                START, source.open(), source.high(), source.low(), source.close(),
                source.volume(), close(START).minusSeconds(1), close(START).plusSeconds(1),
                1, "RAW");
        assertThatThrownBy(() -> partitioner.partition(job,
                page(START, START, List.of(retroactive))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");
        var future = new GoldenDailyAcquisitionPartitioner.Row(
                START, source.open(), source.high(), source.low(), source.close(),
                source.volume(), source.sourcePublishedAt(), NOW.plusSeconds(1),
                1, "RAW");
        assertThatThrownBy(() -> partitioner.partition(job,
                page(START, START, List.of(future))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void invalidDailyOhlcAndNoRowsNeverClaimComplete() {
        var partitioner = prepare(List.of(START));
        var source = row(START);
        var bad = new GoldenDailyAcquisitionPartitioner.Row(
                START, source.open(), new BigDecimal("95"), source.low(),
                source.close(), source.volume(), source.sourcePublishedAt(),
                source.firstAvailableAt(), 1, "RAW");
        assertThatThrownBy(() -> partitioner.partition(job,
                page(START, START, List.of(bad))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent");
        var empty = partitioner.partition(job, page(START, START, List.of()));
        assertThat(empty.complete()).isFalse();
        assertThat(empty.missingSessions()).isEqualTo(1);
        assertThat(empty.validatedBars()).isZero();
    }

    @Test
    void sourcePageCannotBeMutatedAfterValidatedConstruction() {
        byte[] source = "SYNTHETIC".getBytes(StandardCharsets.UTF_8);
        List<GoldenDailyAcquisitionPartitioner.Row> rows = new ArrayList<>();
        rows.add(row(START));
        var page = new GoldenDailyAcquisitionPartitioner.Page(
                listing, "NSE", START, START, source, hash(source), EVIDENCE, rows);
        source[0] = 0;
        rows.clear();
        assertThat(page.nativeBytes()).isEqualTo("SYNTHETIC".getBytes(StandardCharsets.UTF_8));
        assertThat(page.rows()).hasSize(1);
        byte[] accessor = page.nativeBytes();
        accessor[0] = 0;
        assertThat(page.nativeBytes()).isEqualTo("SYNTHETIC".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void identicalSourceAndJobProduceStableChunkIdentityForRetries() {
        var partitioner = prepare(List.of(START));
        var input = page(START, START.plusDays(40), List.of(row(START)));
        var a = partitioner.partition(job, input);
        var b = partitioner.partition(job, input);
        assertThat(a.segments()).hasSize(2);
        assertThat(a.segments().get(0).request().chunkId())
                .isEqualTo(b.segments().get(0).request().chunkId());
        assertThat(a.segments().get(0).request().chunkId())
                .isNotEqualTo(a.segments().get(1).request().chunkId());
    }
}
