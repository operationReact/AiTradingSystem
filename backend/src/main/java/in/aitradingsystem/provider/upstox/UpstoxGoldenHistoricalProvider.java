package in.aitradingsystem.provider.upstox;

import in.aitradingsystem.marketdata.GoldenHistoricalDatasetProvider;
import in.aitradingsystem.marketdata.GoldenSourceRightsRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Dormant source-specific bridge to the existing Golden backfill coordinator.
 * No scheduler or customer route invokes this bean. A reviewed independent
 * validator and immutable archive must be supplied before processNext is used.
 * An API token alone never permits a historical download.
 */
@Component
public final class UpstoxGoldenHistoricalProvider implements GoldenHistoricalDatasetProvider {
    static final String DOMAIN = "historical_equity";
    private final UpstoxAnalyticsSource source;
    private final GoldenSourceRightsRepository rights;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public UpstoxGoldenHistoricalProvider(UpstoxAnalyticsSource source,
            GoldenSourceRightsRepository rights, JdbcTemplate jdbc) {
        this(source, rights, jdbc, Clock.systemUTC());
    }

    UpstoxGoldenHistoricalProvider(UpstoxAnalyticsSource source,
            GoldenSourceRightsRepository rights, JdbcTemplate jdbc, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.rights = Objects.requireNonNull(rights);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public String providerCode() { return "UPSTOX"; }
    @Override public String sourceDomain() { return DOMAIN; }

    @Override
    public SourcePayload fetch(ChunkRequest request) {
        Objects.requireNonNull(request);
        if (!source.enabled() || !rights.isPermitted(providerCode(), DOMAIN,
                GoldenSourceRightsRepository.Use.RAW_RETENTION, clock.instant())) {
            throw new IllegalStateException("UPSTOX_HISTORICAL_RIGHTS_OR_SOURCE_DISABLED");
        }
        if (request.jobId() == null || request.chunkId() == null
                || request.listingId() == null || request.intervalName() == null
                || request.fromDate() == null
                || request.throughDate() == null || request.fromDate().isAfter(request.throughDate())
                || request.maxBytes() < 1 || request.maxBytes() > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("INVALID_UPSTOX_GOLDEN_WINDOW");
        }
        String unit;
        int interval;
        int maximumDays;
        switch (request.intervalName()) {
            case "DAY_1" -> { unit = "days"; interval = 1; maximumDays = 30; }
            case "HOUR_1" -> { unit = "hours"; interval = 1; maximumDays = 30; }
            case "MINUTE_1" -> { unit = "minutes"; interval = 1; maximumDays = 1; }
            default -> throw new IllegalArgumentException("UNSUPPORTED_UPSTOX_GOLDEN_INTERVAL");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(
                request.fromDate(), request.throughDate()) >= maximumDays
                || request.throughDate().isAfter(
                    LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Kolkata"))))) {
            throw new IllegalArgumentException("UNBOUNDED_UPSTOX_GOLDEN_WINDOW");
        }

        // Alias validity uses an exclusive end: an alias ending exactly at
        // endExclusive covers every instant in [start, endExclusive).
        // A provider token is only an episode-bounded alias. Both ends of the
        // requested window must be covered, with no ambiguity or future evidence.
        ZoneId exchangeZone = ZoneId.of("Asia/Kolkata");
        Instant start = request.fromDate().atStartOfDay(exchangeZone).toInstant();
        Instant endExclusive = request.throughDate().plusDays(1)
                .atStartOfDay(exchangeZone).toInstant();
        List<String> aliases = jdbc.query("""
                SELECT a.provider_token
                  FROM golden_provider_symbol_alias a
                  JOIN golden_listing_identity l ON l.listing_id = a.listing_id
                 WHERE a.listing_id = ? AND a.provider = 'UPSTOX'
                   AND a.exchange = 'NSE' AND l.exchange = 'NSE'
                   AND a.valid_from <= ?
                   AND (a.valid_to IS NULL OR a.valid_to >= ?)
                   AND a.first_available_at <= ?
                   AND a.source_published_at <= ?
                """, (rs, row) -> rs.getString(1), request.listingId(),
                java.time.OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                java.time.OffsetDateTime.ofInstant(endExclusive, ZoneOffset.UTC),
                java.time.OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                java.time.OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        if (aliases.size() != 1 || !aliases.get(0).matches("NSE_EQ\\|[A-Z0-9]{12}")) {
            throw new IllegalStateException("UPSTOX_LISTING_ALIAS_NOT_UNAMBIGUOUS");
        }
        // No raw provider payload is logged, exposed to customers or persisted.
        var candles = fetchCandles(request, aliases.get(0), unit, interval);
        // Fail closed before constructing an archive payload. Provider timestamps are
        // exchange instants, while requested dates are exchange-local calendar dates.
        // Reject duplicates and unordered candles rather than silently normalizing them.
        Instant previous = null;
        for (var candle : candles) {
            if (candle == null || candle.time() == null
                    || candle.time().isBefore(start) || !candle.time().isBefore(endExclusive)
                    || (previous != null && !candle.time().isAfter(previous))
                    || candle.open() == null || candle.high() == null
                    || candle.low() == null || candle.close() == null
                    || candle.open().signum() <= 0 || candle.high().signum() <= 0
                    || candle.low().signum() <= 0 || candle.close().signum() <= 0
                    || candle.low().compareTo(candle.high()) > 0
                    || candle.open().compareTo(candle.low()) < 0
                    || candle.open().compareTo(candle.high()) > 0
                    || candle.close().compareTo(candle.low()) < 0
                    || candle.close().compareTo(candle.high()) > 0
                    || candle.volume() < 0 || candle.openInterest() < 0) {
                throw new IllegalStateException("UPSTOX_INVALID_HISTORICAL_CANDLE");
            }
            previous = candle.time();
        }
        StringBuilder canonical = new StringBuilder();
        canonical.append("UPSTOX|").append(DOMAIN).append('|')
                .append(request.listingId()).append('|')
                .append(request.intervalName()).append('\n');
        for (var candle : candles) {
            canonical.append(candle.time()).append(',')
                    .append(candle.open().toPlainString()).append(',')
                    .append(candle.high().toPlainString()).append(',')
                    .append(candle.low().toPlainString()).append(',')
                    .append(candle.close().toPlainString()).append(',')
                    .append(candle.volume()).append(',')
                    .append(candle.openInterest()).append('\n');
        }
        byte[] bytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
        if (candles.isEmpty() || bytes.length > request.maxBytes()) {
            throw new IllegalStateException("UPSTOX_EMPTY_OR_OVERSIZE_WINDOW");
        }
        // Revalidate immediately before handing bytes to the archive coordinator:
        // rights may have expired or been revoked during the upstream request.
        // The initial check alone must never authorize subsequent persistence.
        if (!rights.isPermitted(providerCode(), DOMAIN,
                GoldenSourceRightsRepository.Use.RAW_RETENTION, clock.instant())) {
            throw new IllegalStateException("UPSTOX_HISTORICAL_RIGHTS_REVOKED_DURING_FETCH");
        }
        // This digest identifies normalized source-observation bytes, not an
        // independently attested exchange file or permission to train/display.
        try {
            return new SourcePayload(bytes,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }
    // Translate only the adapter's sanitized 429 code into the durable retry SPI.
    // All other failures remain fail-closed and are not implicitly retried.
    private List<UpstoxAnalyticsSource.Candle> fetchCandles(ChunkRequest request,
            String alias, String unit, int interval) {
        try {
            return source.historicalEquityCandles(alias, unit, interval,
                    request.fromDate(), request.throughDate());
        } catch (IllegalStateException failure) {
            if ("UPSTOX_RATE_LIMITED".equals(failure.getMessage())) {
                throw new RetryableSourceException("UPSTOX_RATE_LIMITED");
            }
            throw failure;
        }
    }
}
