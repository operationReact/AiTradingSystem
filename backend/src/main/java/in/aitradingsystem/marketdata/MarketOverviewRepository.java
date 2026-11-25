package in.aitradingsystem.marketdata;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only reference data for the Markets overview: index definitions, universe members and tokens. */
@Repository
public class MarketOverviewRepository {
    private final JdbcTemplate jdbc;

    public MarketOverviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record IndexDefinition(String indexCode, String displayName, String symbolToken) {}

    public record Member(String tradingSymbol, String companyName, String sector, LocalDate asOf, String source) {}

    public List<IndexDefinition> indices() {
        return jdbc.query("""
                SELECT index_code, display_name, symbol_token
                  FROM market_index_definition
                 ORDER BY sort_order
                """, (rs, row) -> new IndexDefinition(
                rs.getString("index_code"), rs.getString("display_name"), rs.getString("symbol_token")));
    }

    public List<Member> universe(String universeCode) {
        return jdbc.query("""
                SELECT trading_symbol, company_name, sector, as_of, source
                  FROM market_universe_member
                 WHERE universe_code = ?
                 ORDER BY trading_symbol
                """, (rs, row) -> new Member(rs.getString("trading_symbol"), rs.getString("company_name"),
                rs.getString("sector"), rs.getObject("as_of", LocalDate.class), rs.getString("source")),
                universeCode);
    }

    /** Symbol tokens for cash equities present in the active catalogue only; absent symbols are omitted. */
    public Map<String, String> resolveEquityTokens(List<String> tradingSymbols) {
        Map<String, String> tokens = new HashMap<>();
        if (tradingSymbols.isEmpty()) return tokens;
        String placeholders = String.join(",", tradingSymbols.stream().map(s -> "?").toList());
        List<Object> args = new ArrayList<>(tradingSymbols);
        jdbc.query("""
                SELECT i.trading_symbol, i.symbol_token
                  FROM instrument_master i
                  JOIN instrument_catalogue_state s
                    ON s.singleton_id = 1 AND s.active_version = i.catalogue_version
                 WHERE i.active = TRUE AND i.exchange = 'NSE' AND i.exchange_segment = 'NSE_CM'
                   AND i.trading_symbol IN (""" + placeholders + ")",
                (rs) -> {
                    tokens.putIfAbsent(rs.getString("trading_symbol"), rs.getString("symbol_token"));
                }, args.toArray());
        return tokens;
    }
}
