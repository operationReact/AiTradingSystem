package in.aitradingsystem.marketdata;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Materialises an archived official index constituent list into {@code market_universe_member}.
 * A universe is replaced atomically, so readers never see a half-applied list.
 */
@Repository
public class MarketUniverseMembershipStore {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public MarketUniverseMembershipStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Parses and applies an archived constituent snapshot; non-constituent families are ignored. */
    public int materialize(OfficialMarketSourceSnapshot snapshot) {
        var universe = IndexConstituentCsv.universeFor(snapshot.sourceFamily());
        if (universe.isEmpty()) return 0;
        List<IndexConstituentCsv.Member> members =
            IndexConstituentCsv.parse(snapshot.payload(), universe.get());
        LocalDate asOf = snapshot.observedAt().atZone(MARKET_ZONE).toLocalDate();
        String source = "NSE_" + snapshot.sourceFamily().name() + "_CSV";
        replace(universe.get().code(), members, source, asOf);
        return members.size();
    }

    void replace(String universeCode, List<IndexConstituentCsv.Member> members, String source, LocalDate asOf) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM market_universe_member WHERE universe_code = ?", universeCode);
            jdbc.batchUpdate("""
                    INSERT INTO market_universe_member
                        (universe_code, trading_symbol, company_name, sector, source, as_of)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, members, 200, (ps, member) -> {
                ps.setString(1, universeCode);
                ps.setString(2, member.tradingSymbol());
                ps.setString(3, member.companyName());
                ps.setString(4, member.industry());
                ps.setString(5, source);
                ps.setDate(6, Date.valueOf(asOf));
            });
        });
    }
}
