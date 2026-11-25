package in.aitradingsystem.watchlist;

import in.aitradingsystem.instrument.Instrument;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Owner-scoped watchlist persistence. Every query is bound to the authenticated customer's user ID. */
@Repository
public class CustomerWatchlistStore {
    public static final int MAX_ITEMS = 100;

    public enum AddOutcome { ADDED, ALREADY_PRESENT, LIMIT_REACHED }

    public record Item(long instrumentId, String exchange, String tradingSymbol, Instant addedAt) {}

    private final JdbcClient jdbc;

    public CustomerWatchlistStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Item> list(UUID userId) {
        return jdbc.sql("""
                SELECT instrument_id, exchange, trading_symbol, added_at
                  FROM customer_watchlist_item
                 WHERE user_id = :userId
                 ORDER BY added_at, instrument_id
                """)
            .param("userId", userId)
            .query((rs, row) -> new Item(rs.getLong("instrument_id"), rs.getString("exchange"),
                rs.getString("trading_symbol"), rs.getTimestamp("added_at").toInstant()))
            .list();
    }

    /** Adds the item if the owner is under the limit. Re-adding an existing item is idempotent. */
    public AddOutcome add(UUID userId, Instrument instrument) {
        int inserted;
        try {
            inserted = jdbc.sql("""
                    INSERT INTO customer_watchlist_item (user_id, instrument_id, exchange, trading_symbol)
                    SELECT :userId, :instrumentId, :exchange, :tradingSymbol
                     WHERE (SELECT count(*) FROM customer_watchlist_item WHERE user_id = :userId) < :maxItems
                    ON CONFLICT (user_id, instrument_id) DO NOTHING
                    """)
                .param("userId", userId)
                .param("instrumentId", instrument.id())
                .param("exchange", instrument.exchange())
                .param("tradingSymbol", instrument.tradingSymbol())
                .param("maxItems", MAX_ITEMS)
                .update();
        } catch (DuplicateKeyException race) {
            inserted = 0;
        }
        if (inserted == 1) {
            return AddOutcome.ADDED;
        }
        boolean present = jdbc.sql("""
                SELECT count(*) FROM customer_watchlist_item
                 WHERE user_id = :userId AND instrument_id = :instrumentId
                """)
            .param("userId", userId)
            .param("instrumentId", instrument.id())
            .query(Long.class).single() > 0;
        return present ? AddOutcome.ALREADY_PRESENT : AddOutcome.LIMIT_REACHED;
    }

    /**
     * Moves an owner's item from a stale catalogue ID to the current one. When the owner already holds
     * the current ID, the stale row is removed instead, so the owner never gets duplicate items.
     */
    public void repoint(UUID userId, long fromInstrumentId, long toInstrumentId) {
        try {
            int moved = jdbc.sql("""
                    UPDATE customer_watchlist_item SET instrument_id = :toId
                     WHERE user_id = :userId AND instrument_id = :fromId
                       AND NOT EXISTS (SELECT 1 FROM customer_watchlist_item
                                        WHERE user_id = :userId AND instrument_id = :toId)
                    """)
                .param("userId", userId)
                .param("fromId", fromInstrumentId)
                .param("toId", toInstrumentId)
                .update();
            if (moved == 0) {
                remove(userId, fromInstrumentId);
            }
        } catch (DuplicateKeyException race) {
            remove(userId, fromInstrumentId);
        }
    }

    /** Removes the item for this owner only. Returns false when it was not present. */
    public boolean remove(UUID userId, long instrumentId) {
        return jdbc.sql("""
                DELETE FROM customer_watchlist_item WHERE user_id = :userId AND instrument_id = :instrumentId
                """)
            .param("userId", userId)
            .param("instrumentId", instrumentId)
            .update() == 1;
    }
}
