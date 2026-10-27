package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketDataInstrumentRepository {
    private final JdbcTemplate jdbc;

    public MarketDataInstrumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int upsertBatch(List<InstrumentMasterService.MasterRow> rows) {
        String sql = """
            INSERT INTO market_instrument
                (exchange, symbol_token, trading_symbol, name, instrument_type, expiry,
                 strike_price, lot_size, tick_size, source, source_updated_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ANGEL_ONE_MASTER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (exchange, symbol_token) DO UPDATE SET
                trading_symbol = EXCLUDED.trading_symbol,
                name = EXCLUDED.name,
                instrument_type = EXCLUDED.instrument_type,
                expiry = EXCLUDED.expiry,
                strike_price = EXCLUDED.strike_price,
                lot_size = EXCLUDED.lot_size,
                tick_size = EXCLUDED.tick_size,
                source = EXCLUDED.source,
                source_updated_at = EXCLUDED.source_updated_at,
                updated_at = CURRENT_TIMESTAMP
            """;
        jdbc.batchUpdate(sql, rows, 500,
            (ps, row) -> {
                ps.setString(1, row.exchange());
                ps.setString(2, row.symbolToken());
                ps.setString(3, row.tradingSymbol());
                ps.setString(4, row.name());
                ps.setString(5, row.instrumentType());
                if (row.expiry() == null) ps.setNull(6, java.sql.Types.DATE);
                else ps.setDate(6, Date.valueOf(row.expiry()));
                ps.setBigDecimal(7, row.strikePrice() == null ? BigDecimal.ZERO : row.strikePrice());
                if (row.lotSize() == null) ps.setNull(8, java.sql.Types.INTEGER);
                else ps.setInt(8, row.lotSize());
                ps.setBigDecimal(9, row.tickSize() == null ? BigDecimal.ZERO : row.tickSize());
            });
        return rows.size();
    }

    public List<MarketInstrument> search(String query, String exchange, int limit) {
        String pattern = "%" + (query == null ? "" : query.trim().toUpperCase()) + "%";
        return jdbc.query("""
            SELECT id, exchange, symbol_token, trading_symbol, name, instrument_type,
                   expiry, strike_price, lot_size, tick_size
              FROM market_instrument
             WHERE (? = '%%' OR upper(trading_symbol) LIKE ? OR upper(coalesce(name, '')) LIKE ?)
               AND (? IS NULL OR exchange = ?)
             ORDER BY exchange, trading_symbol
             LIMIT ?
            """, (rs, rowNum) -> new MarketInstrument(
                rs.getLong("id"), rs.getString("exchange"), rs.getString("symbol_token"),
                rs.getString("trading_symbol"), rs.getString("name"), rs.getString("instrument_type"),
                rs.getDate("expiry") == null ? null : rs.getDate("expiry").toLocalDate(),
                rs.getBigDecimal("strike_price"), (Integer) rs.getObject("lot_size"), rs.getBigDecimal("tick_size")
            ), pattern, pattern, pattern, exchange, exchange, limit);
    }
}