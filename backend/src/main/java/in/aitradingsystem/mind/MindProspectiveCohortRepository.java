package in.aitradingsystem.mind;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Immutable, one-time cohort registration from the active NSE cash catalogue.
 * Registration never reads signals, prices, realized returns or backtests.
 * Catalogue state is SHARE-locked until the full cohort is inserted.
 */
@Repository
public class MindProspectiveCohortRepository {
    public static final String CODE = "MA_RECLAIM_NSE_EQ_PROSPECTIVE_V1";
    public static final String PROTOCOL = "MIND_PROSPECTIVE_04C1_V1";
    public static final int SIZE = 20;
    public static final String SELECTION = "DETERMINISTIC_CATALOGUE_HASH_NO_SIGNAL_RANKING";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public record Member(int slot, long instrumentId, UUID catalogueVersion,
                         String exchange, String tradingSymbol, String symbolToken) {}

    public record Cohort(UUID id, String cohortCode, String protocolVersion,
                         String strategyCode, UUID catalogueVersion,
                         Instant catalogueActivatedAt, Instant registeredAt,
                         Instant observationStartsAt, int targetSessions,
                         List<Member> members) {}

    public record Observation(int slot, Instant candleTime, Instant fetchedAt, String source,
                              BigDecimal open, BigDecimal high, BigDecimal low,
                              BigDecimal close, long volume) {}

    private record Catalogue(UUID version, Instant activatedAt) {}

    public MindProspectiveCohortRepository(JdbcTemplate jdbc,
                                            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    public Cohort register(UUID id, Instant recordedAt, Instant startsAt) {
        if (id == null || recordedAt == null || startsAt == null ||
            !startsAt.isAfter(recordedAt)) {
            throw new IllegalArgumentException("Prospective registration must precede observations");
        }
        return transaction.execute(status -> {
            // A simultaneous catalogue import cannot change the selected
            // source version until registration has committed.
            var catalogue = jdbc.query("""
                    SELECT active_version, activated_at
                      FROM instrument_catalogue_state
                     WHERE singleton_id = 1
                     FOR SHARE
                    """, (rs, n) -> new Catalogue(
                    rs.getObject("active_version", UUID.class),
                    instant(rs.getObject("activated_at", OffsetDateTime.class))))
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("CATALOGUE_UNAVAILABLE"));
            if (catalogue.version() == null || catalogue.activatedAt() == null ||
                catalogue.activatedAt().isAfter(recordedAt)) {
                throw new IllegalStateException("CATALOGUE_NOT_KNOWN_AT_REGISTRATION");
            }
            if (current().isPresent()) {
                throw new IllegalStateException("PROSPECTIVE_COHORT_ALREADY_REGISTERED");
            }

            // Hash-sort the published, active cash catalogue. No research
            // candidate score or historical return is consulted. Reproducible,
            // but not a market-cap weighted or liquidity-qualified cohort.
            var members = jdbc.query("""
                    SELECT instrument_id, catalogue_version, exchange,
                           trading_symbol, symbol_token
                      FROM instrument_master
                     WHERE active = TRUE AND catalogue_version = ?
                       AND exchange_segment = 'NSE_CM' AND exchange = 'NSE'
                       AND trading_symbol ~ '^[A-Z0-9._&-]+-EQ$'
                       AND upper(trading_symbol) NOT LIKE '%TEST%'
                       AND symbol_token IS NOT NULL AND length(trim(symbol_token)) > 0
                     ORDER BY md5(trading_symbol || ':' || catalogue_version::text),
                              trading_symbol, instrument_id
                     LIMIT ?
                    """, (rs, n) -> new Member(n + 1, rs.getLong("instrument_id"),
                    rs.getObject("catalogue_version", UUID.class),
                    rs.getString("exchange"), rs.getString("trading_symbol"),
                    rs.getString("symbol_token")), catalogue.version(), SIZE);
            if (members.size() != SIZE ||
                members.stream().map(Member::tradingSymbol).distinct().count() != SIZE ||
                members.stream().map(Member::instrumentId).distinct().count() != SIZE) {
                throw new IllegalStateException("INSUFFICIENT_UNIQUE_CASH_EQUITIES");
            }
            jdbc.update("""
                    INSERT INTO mind_prospective_cohort
                      (id, cohort_code, protocol_version, strategy_code,
                       catalogue_version, catalogue_activated_at, registered_at,
                       observation_starts_at, target_sessions, member_count,
                       selection_method)
                    VALUES (?, ?, ?, 'MA_RECLAIM_V1', ?, ?, ?, ?, 10, 20, ?)
                    """, id, CODE, PROTOCOL, catalogue.version(),
                    utc(catalogue.activatedAt()), utc(recordedAt),
                    utc(startsAt), SELECTION);
            for (var member : members) {
                jdbc.update("""
                        INSERT INTO mind_prospective_member
                          (cohort_id, slot, instrument_id, catalogue_version,
                           exchange, trading_symbol, symbol_token)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, id, member.slot(), member.instrumentId(),
                        member.catalogueVersion(), member.exchange(),
                        member.tradingSymbol(), member.symbolToken());
            }
            return new Cohort(id, CODE, PROTOCOL, "MA_RECLAIM_V1",
                    catalogue.version(), catalogue.activatedAt(),
                    recordedAt, startsAt, 10, List.copyOf(members));
        });
    }

    public Optional<Cohort> current() {
        var rows = jdbc.query("""
                SELECT id, cohort_code, protocol_version, strategy_code,
                       catalogue_version, catalogue_activated_at, registered_at,
                       observation_starts_at, target_sessions
                  FROM mind_prospective_cohort
                 WHERE cohort_code = ?
                """, (rs, n) -> new Cohort(
                rs.getObject("id", UUID.class), rs.getString("cohort_code"),
                rs.getString("protocol_version"), rs.getString("strategy_code"),
                rs.getObject("catalogue_version", UUID.class),
                instant(rs.getObject("catalogue_activated_at", OffsetDateTime.class)),
                instant(rs.getObject("registered_at", OffsetDateTime.class)),
                instant(rs.getObject("observation_starts_at", OffsetDateTime.class)),
                rs.getInt("target_sessions"), List.of()), CODE);
        if (rows.isEmpty()) return Optional.empty();
        Cohort c = rows.getFirst();
        var members = jdbc.query("""
                SELECT slot, instrument_id, catalogue_version, exchange,
                       trading_symbol, symbol_token
                  FROM mind_prospective_member
                 WHERE cohort_id = ?
                 ORDER BY slot
                """, (rs, n) -> new Member(rs.getInt("slot"), rs.getLong("instrument_id"),
                    rs.getObject("catalogue_version", UUID.class),
                    rs.getString("exchange"), rs.getString("trading_symbol"),
                    rs.getString("symbol_token")), c.id());
        return Optional.of(new Cohort(c.id(), c.cohortCode(), c.protocolVersion(),
                c.strategyCode(), c.catalogueVersion(), c.catalogueActivatedAt(),
                c.registeredAt(), c.observationStartsAt(), c.targetSessions(),
                List.copyOf(members)));
    }

    /**
     * One bounded, read-only set of recorded five-minute bars for future
     * observation days. Source and fetchedAt must still pass service checks.
     */
    public List<Observation> observations(UUID cohortId, Instant from, Instant to, int limit) {
        if (cohortId == null || from == null || to == null || !to.isAfter(from) ||
            limit < 1 || limit > 20001) throw new IllegalArgumentException("Invalid observation window");
        return jdbc.query("""
                SELECT m.slot, c.candle_time, c.fetched_at, c.source,
                       c.open_price, c.high_price, c.low_price, c.close_price, c.volume
                  FROM mind_prospective_member m
                  JOIN mind_prospective_cohort h ON h.id = m.cohort_id
                  JOIN market_candle c
                    ON c.instrument_id = m.instrument_id
                   AND c.catalogue_version = m.catalogue_version
                   AND c.interval = 'FIVE_MINUTE'
                   AND c.exchange = m.exchange
                   AND c.trading_symbol = m.trading_symbol
                 WHERE m.cohort_id = ?
                   AND c.candle_time >= ? AND c.candle_time < ?
                   AND c.fetched_at >= h.registered_at
                 ORDER BY m.slot, c.candle_time
                 LIMIT ?
                """, (rs, n) -> new Observation(
                    rs.getInt("slot"),
                    instant(rs.getObject("candle_time", OffsetDateTime.class)),
                    instant(rs.getObject("fetched_at", OffsetDateTime.class)),
                    rs.getString("source"),
                    rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"),
                    rs.getBigDecimal("low_price"), rs.getBigDecimal("close_price"),
                    rs.getLong("volume")),
                cohortId, utc(from), utc(to), limit);
    }

    private static OffsetDateTime utc(Instant x) {
        return OffsetDateTime.ofInstant(x, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime x) {
        return x == null ? null : x.toInstant();
    }
}
