package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Synthetic daily bars and session attestations only; not licensed NSE data,
 * not a verified 2006-2026 exchange calendar or production data readiness.
 */
class GoldenDailyBarValidatorTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final LocalDate FRI = LocalDate.of(2026, 11, 6);
    private static final LocalDate SAT = LocalDate.of(2026, 11, 7);
    private static final LocalDate SUN = LocalDate.of(2026, 11, 8);
    private static final Instant FRI_CLOSE = Instant.parse("2026-11-06T10:00:00Z");
    private static final Instant SUN_CLOSE = Instant.parse("2026-11-08T09:45:00Z");

    private final UUID listing = UUID.randomUUID();
    private final GoldenDailyBarValidator validator =
            new GoldenDailyBarValidator(id -> new GoldenHistoricalListingEvidence.ListingEpisode(
                    id, "NSE", LocalDate.of(2000, 1, 1), null, HASH),
                    this::evidence, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void verifiesOneFridayAndExplicitSpecialSundayButNotClosedSaturday() {
        var result = validator.inspect(request(),
                source(row(FRI) + "\n" + row(SUN)));
        assertThat(result.expectedSessions()).isEqualTo(2L);
        assertThat(result.validBars()).isEqualTo(2L);
        assertThat(result.missingSessions()).isEmpty();
        assertThat(result.complete()).isTrue();
        assertThat(result.firstAvailableAt()).isEqualTo(
                Instant.parse("2026-11-06T10:10:00Z"));
        assertThat(result.lastAvailableAt()).isEqualTo(
                Instant.parse("2026-11-08T10:00:00Z"));
    }

    @Test
    void completeCalendarWithoutSundayCandleIsIncompleteNotSuccessful() {
        var result = validator.inspect(request(), source(row(FRI)));
        assertThat(result.expectedSessions()).isEqualTo(2L);
        assertThat(result.validBars()).isEqualTo(1L);
        assertThat(result.missingSessions()).containsExactly(SUN);
        assertThat(result.complete()).isFalse();
        assertThat(validator.validate(request(), source(row(FRI))).validRecords())
                .isEqualTo(1);
    }

    @Test
    void emptyResponseNeverConfirmsAValidDailyDataset() {
        var result = validator.inspect(request(), source(""));
        assertThat(result.expectedSessions()).isEqualTo(2L);
        assertThat(result.validBars()).isZero();
        assertThat(result.complete()).isFalse();
        assertThat(result.missingSessions()).containsExactly(FRI, SUN);
        assertThat(result.firstAvailableAt()).isNull();
    }

    @Test
    void duplicateOrOutOfOrderSessionsQuarantineWholeChunk() {
        assertThatThrownBy(() -> validator.inspect(request(),
                source(row(FRI) + "\n" + row(FRI))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.inspect(request(),
                source(row(SUN) + "\n" + row(FRI))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void barsOnClosedWeekendsAreNotInferredAsValidTrading() {
        assertThatThrownBy(() -> validator.inspect(request(), source(row(SAT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closed-session");
    }

    @Test
    void calendarUnknownOrMissingOrFutureAttestationIsNotTreatedAsHoliday() {
        var unknown = new GoldenDailyBarValidator(
                id -> listingEvidence(id),
                (exchange, date) -> new GoldenHistoricalSessionEvidence.SessionEvidence(
                        GoldenHistoricalSessionEvidence.Status.UNKNOWN, null, HASH, NOW.minusSeconds(2)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> unknown.inspect(request(), source(row(FRI))))
                .isInstanceOf(IllegalStateException.class);
        var future = new GoldenDailyBarValidator(
                id -> listingEvidence(id),
                (exchange, date) -> new GoldenHistoricalSessionEvidence.SessionEvidence(
                        GoldenHistoricalSessionEvidence.Status.CLOSED, null, HASH, NOW.plusSeconds(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> future.inspect(request(), source(row(FRI))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectAdjustedPricesRatherThanApplyingCorporateActionsRetroactively() {
        assertThatThrownBy(() -> validator.inspect(request(),
                source(row(FRI).replace(",RAW", ",SPLIT_ADJUSTED"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price basis");
    }

    @Test
    void rejectedFutureOrPreClosePublishedDataCannotLeakIntoPointInTimeHistory() {
        assertThatThrownBy(() -> validator.inspect(request(),
                source(row(FRI).replace("2026-11-06T10:01:00Z",
                        "2026-11-06T09:59:59Z"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> validator.inspect(request(),
                source(row(FRI).replace("2026-11-06T10:10:00Z",
                        "2026-11-11T10:10:00Z"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void incorrectIdentityAndNonDailySourceAreRejected() {
        assertThatThrownBy(() -> validator.inspect(request(),
                source(row(FRI).replace(listing.toString(), UUID.randomUUID().toString()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wrong listing");
        var fiveMinute = new GoldenHistoricalDatasetProvider.ChunkRequest(
                UUID.randomUUID(), UUID.randomUUID(), listing, "FIVE_MINUTE",
                FRI, SUN, 4096);
        assertThatThrownBy(() -> validator.inspect(fiveMinute, source(row(FRI))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outOfListingEpisodeIsNeverReassignedToCurrentBrokerSymbol() {
        var late = new GoldenDailyBarValidator(
                id -> new GoldenHistoricalListingEvidence.ListingEpisode(
                        id, "NSE", LocalDate.of(2026, 11, 8), null, HASH),
                this::evidence, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> late.inspect(request(), source(row(FRI))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("listing episode");
    }

    @Test
    void invalidOhlcVolumeAndOverPrecisionQuarantineInsteadOfSkipping() {
        assertThatThrownBy(() -> validator.inspect(request(),
                source(row(FRI).replace(",110,90,105,100,", ",80,90,105,100,"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.inspect(request(),
                source(row(FRI).replace(",1000,", ",0,"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.inspect(request(),
                source(row(FRI).replace(",110,90,105,100,", ",110.12345678901,90,105,100,"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedUtf8OrHeaderDoesNotBecomeValidatedCoverage() {
        var payload = new GoldenHistoricalDatasetProvider.SourcePayload(
                new byte[] {(byte) 0xc3, (byte) 0x28}, HASH);
        assertThatThrownBy(() -> validator.inspect(request(), payload))
                .isInstanceOf(IllegalArgumentException.class);
        var corrupted = new GoldenHistoricalDatasetProvider.SourcePayload(
                ("other_header\n" + row(FRI)).getBytes(StandardCharsets.UTF_8), HASH);
        assertThatThrownBy(() -> validator.inspect(request(), corrupted))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GoldenHistoricalListingEvidence.ListingEpisode listingEvidence(UUID id) {
        return new GoldenHistoricalListingEvidence.ListingEpisode(
                id, "NSE", LocalDate.of(2000, 1, 1), null, HASH);
    }

    private GoldenHistoricalSessionEvidence.SessionEvidence evidence(
            String exchange, LocalDate date) {
        if (!"NSE".equals(exchange)) {
            return new GoldenHistoricalSessionEvidence.SessionEvidence(
                    GoldenHistoricalSessionEvidence.Status.UNKNOWN, null, HASH, NOW);
        }
        if (date.equals(FRI)) return session(FRI_CLOSE);
        if (date.equals(SUN)) return session(SUN_CLOSE);
        if (date.equals(SAT)) {
            return new GoldenHistoricalSessionEvidence.SessionEvidence(
                    GoldenHistoricalSessionEvidence.Status.CLOSED, null, HASH,
                    Instant.parse("2026-11-06T14:00:00Z"));
        }
        return new GoldenHistoricalSessionEvidence.SessionEvidence(
                GoldenHistoricalSessionEvidence.Status.UNKNOWN, null, HASH, NOW);
    }

    private GoldenHistoricalSessionEvidence.SessionEvidence session(Instant close) {
        return new GoldenHistoricalSessionEvidence.SessionEvidence(
                GoldenHistoricalSessionEvidence.Status.TRADING,
                close, HASH, Instant.parse("2026-11-09T10:00:00Z"));
    }

    private GoldenHistoricalDatasetProvider.ChunkRequest request() {
        return new GoldenHistoricalDatasetProvider.ChunkRequest(
                UUID.randomUUID(), UUID.randomUUID(), listing, "ONE_DAY", FRI, SUN, 4096);
    }

    private GoldenHistoricalDatasetProvider.SourcePayload source(String bars) {
        return new GoldenHistoricalDatasetProvider.SourcePayload(
                (GoldenDailyBarValidator.HEADER + "\n" + bars
                        + (bars.isEmpty() ? "" : "\n")).getBytes(StandardCharsets.UTF_8), HASH);
    }

    private String row(LocalDate day) {
        String published = day.equals(SUN)
                ? "2026-11-08T09:50:00Z" : "2026-11-06T10:01:00Z";
        String available = day.equals(SUN)
                ? "2026-11-08T10:00:00Z" : "2026-11-06T10:10:00Z";
        return String.join(",", listing.toString(), "NSE", day.toString(),
                "105", "110", "90", "100", "1000", published, available, "1", "RAW");
    }
}
