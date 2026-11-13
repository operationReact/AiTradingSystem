package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read model for the customer Markets board: the latest verified FULL quote stored for every
 * active NSE cash equity, joined to official index membership (cap segment and industry).
 * Nothing is estimated; an instrument without a stored verified quote is simply absent.
 */
@Repository
public class MarketBoardRepository {
    private final JdbcTemplate jdbc;

    public MarketBoardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One stored provider FULL quote with its official index memberships. */
    public record StoredQuote(long instrumentId, String tradingSymbol, String companyName, String industry,
                              Set<String> universes, BigDecimal ltp, BigDecimal open, BigDecimal high,
                              BigDecimal low, BigDecimal previousClose, long volume, BigDecimal week52High,
                              BigDecimal week52Low, Instant exchangeAt) {}

    /** An active NSE cash equity that belongs to at least one official index list. */
    public record RefreshTarget(long instrumentId, String tradingSymbol, String symbolToken) {}

    public record UniverseState(String universeCode, int members, LocalDate asOf) {}

    private static final String ELIGIBLE = """
            SELECT i.instrument_id, i.trading_symbol, i.instrument_name, i.symbol_token
              FROM instrument_master i
              JOIN instrument_catalogue_state s
                ON s.singleton_id = 1 AND s.active_version = i.catalogue_version
             WHERE i.active = TRUE AND i.exchange = 'NSE' AND i.exchange_segment = 'NSE_CM'
               AND i.trading_symbol LIKE '%-EQ'
               AND upper(i.trading_symbol) NOT LIKE '%NSETEST%'
            """;

    public List<StoredQuote> storedQuotes() {
        return jdbc.query("""
                WITH eligible AS (""" + ELIGIBLE + """
                ), membership AS (
                    SELECT trading_symbol,
                           string_agg(universe_code, ',' ORDER BY universe_code) AS universes,
                           max(company_name) AS company_name,
                           max(sector) AS industry
                      FROM market_universe_member GROUP BY trading_symbol
                )
                SELECT e.instrument_id, e.trading_symbol,
                       coalesce(m.company_name, e.instrument_name) AS company_name,
                       m.industry, m.universes,
                       (d.payload->>'ltp')::numeric AS ltp,
                       (d.payload->>'open')::numeric AS open_price,
                       (d.payload->>'high')::numeric AS high_price,
                       (d.payload->>'low')::numeric AS low_price,
                       (d.payload->>'previousClose')::numeric AS previous_close,
                       (d.payload->>'volume')::bigint AS volume,
                       (d.payload->>'week52High')::numeric AS week52_high,
                       (d.payload->>'week52Low')::numeric AS week52_low,
                       (d.payload->>'exchangeAt')::timestamptz AS exchange_at
                  FROM eligible e
                  JOIN market_detail_snapshot d ON d.instrument_id = e.instrument_id
                  LEFT JOIN membership m ON m.trading_symbol = e.trading_symbol
                 WHERE d.payload->>'source' IN ('ANGEL_ONE_SMARTAPI_FULL_APP', 'STORED_LAST_DETAIL')
                   AND d.payload->>'exchange' = 'NSE'
                   AND d.payload->>'tradingSymbol' = e.trading_symbol
                   AND (d.payload->>'instrumentId')::bigint = e.instrument_id
                   AND (d.payload->>'ltp')::numeric > 0
                   AND (d.payload->>'previousClose')::numeric > 0
                """, MarketBoardRepository::storedQuote);
    }

    public long activeNseListings() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM (" + ELIGIBLE + ") e", Long.class);
        return count == null ? 0 : count;
    }

    public List<UniverseState> universes() {
        return jdbc.query("""
                SELECT universe_code, COUNT(*) AS members, MAX(as_of) AS as_of
                  FROM market_universe_member GROUP BY universe_code ORDER BY universe_code
                """, (rs, row) -> new UniverseState(rs.getString("universe_code"),
                rs.getInt("members"), rs.getObject("as_of", LocalDate.class)));
    }

    /** Index members that resolve to the active NSE catalogue; used for bounded intraday refresh. */
    public List<RefreshTarget> refreshTargets(Set<String> universeCodes) {
        if (universeCodes.isEmpty()) return List.of();
        String placeholders = universeCodes.stream().map(code -> "?").collect(Collectors.joining(","));
        return jdbc.query("""
                WITH eligible AS (""" + ELIGIBLE + """
                )
                SELECT DISTINCT e.instrument_id, e.trading_symbol, e.symbol_token
                  FROM eligible e
                  JOIN market_universe_member m ON m.trading_symbol = e.trading_symbol
                 WHERE m.universe_code IN (""" + placeholders + """
                ) ORDER BY e.trading_symbol
                """, (rs, row) -> new RefreshTarget(rs.getLong("instrument_id"),
                rs.getString("trading_symbol"), rs.getString("symbol_token")),
                universeCodes.toArray());
    }

    private static StoredQuote storedQuote(ResultSet rs, int row) throws SQLException {
        String universes = rs.getString("universes");
        Timestamp at = rs.getTimestamp("exchange_at");
        return new StoredQuote(rs.getLong("instrument_id"), rs.getString("trading_symbol"),
            rs.getString("company_name"), rs.getString("industry"),
            universes == null ? Set.of() : Set.copyOf(Arrays.asList(universes.split(","))),
            rs.getBigDecimal("ltp"), rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"),
            rs.getBigDecimal("low_price"), rs.getBigDecimal("previous_close"), rs.getLong("volume"),
            rs.getBigDecimal("week52_high"), rs.getBigDecimal("week52_low"),
            at == null ? null : at.toInstant());
    }
}
