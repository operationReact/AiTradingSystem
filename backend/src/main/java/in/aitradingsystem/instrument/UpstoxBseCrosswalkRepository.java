package in.aitradingsystem.instrument;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Source-custodied cross-broker BSE cash-equity classification. Dated by actual
 * fetch time (Upstox BOD file has no embedded publication date), expires after
 * 36 hours. Historical catalogue versions are never revised.
 */
@Repository
public class UpstoxBseCrosswalkRepository {
    private static final String SOURCE =
        "https://assets.upstox.com/market-quote/instruments/exchange/BSE.json.gz";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public record Status(UUID version, Instant fetchedAt, int totalRows,
                         int eligibleRows, int excludedRows, String sha256) {}
    public record Reconciliation(int brokerBseRaw, int matched, int unclassified,
                                 int ambiguous, int sourceEquities) {}

    public UpstoxBseCrosswalkRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    public Status current() {
        return jdbc.query("""
            SELECT s.active_import, i.source_fetched_at, i.source_total_rows,
                   i.eligible_rows, i.excluded_rows, i.content_sha256
              FROM upstox_bse_reference_active s
              LEFT JOIN upstox_bse_reference_import i ON i.id = s.active_import
             WHERE s.singleton_id=1
            """, (rs, idx) -> new Status(rs.getObject(1, UUID.class),
                rs.getObject(2, OffsetDateTime.class) == null ? null :
                    rs.getObject(2, OffsetDateTime.class).toInstant(),
                rs.getInt(3),rs.getInt(4),rs.getInt(5),rs.getString(6)))
            .stream().findFirst().orElse(new Status(null,null,0,0,0,null));
    }

    public Status publish(UpstoxBseCrosswalkParser.Parsed source, String sha, Instant fetchedAt) {
        if (source == null || source.eligibleRows() < 100 || source.totalRows() < source.eligibleRows()
                || source.eligibleRows() != source.verifiedEquities().size()
                || source.excludedRows() != source.totalRows() - source.eligibleRows()
                || sha == null || !sha.matches("[0-9a-f]{64}") || fetchedAt == null) {
            throw invalid("Upstox secondary BSE reference failed minimum source coverage");
        }
        return transaction.execute(ignored -> {
            List<UUID> singleton = jdbc.query("""
                SELECT active_import FROM upstox_bse_reference_active
                 WHERE singleton_id=1 FOR UPDATE
                """, (rs,row) -> rs.getObject(1, UUID.class));
            if (singleton.isEmpty()) throw invalid("BSE secondary reference state missing");
            Status active = current();
            if (active.fetchedAt() != null && fetchedAt.isBefore(active.fetchedAt())) {
                throw invalid("Older BSE secondary source may not replace newer source");
            }
            if (active.version() != null && (source.eligibleRows() * 100L < active.eligibleRows() * 90L
                    || source.totalRows() * 100L < active.totalRows() * 90L)) {
                throw invalid("BSE secondary crosswalk has unexpectedly lost coverage");
            }
            // Record each verified daily download as its own immutable observation,
            // even when the provider publishes identical bytes on consecutive days.
            UUID id = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO upstox_bse_reference_import
                  (id, source_url, content_sha256, source_fetched_at,
                   source_total_rows, eligible_rows, excluded_rows)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, SOURCE, sha, OffsetDateTime.ofInstant(fetchedAt, java.time.ZoneOffset.UTC),
                    source.totalRows(), source.eligibleRows(), source.excludedRows());
            var entries = new ArrayList<>(source.verifiedEquities().values());
            for (int off=0; off < entries.size(); off+=500) {
                var chunk=entries.subList(off, Math.min(off+500,entries.size()));
                jdbc.batchUpdate("""
                    INSERT INTO upstox_bse_reference_equity
                      (import_id, exchange_token, trading_symbol, isin, instrument_key)
                    VALUES (?, ?, ?, ?, ?)
                    """, chunk,chunk.size(),(PreparedStatement ps,UpstoxBseCrosswalkParser.Equity row)->{
                        ps.setObject(1,id);
                        ps.setString(2,row.code());
                        ps.setString(3,row.symbol());
                        ps.setString(4,row.isin());
                        ps.setString(5,row.key());
                    });
            }
            jdbc.update("""
                UPDATE upstox_bse_reference_active
                   SET active_import=?, activated_at=now() WHERE singleton_id=1
                """,id);
            return current();
        });
    }

    /** Only currently fetched (36h) secondary equity identities are eligible. */
    public Map<String, String> recentVerifiedIdentities() {
        return jdbc.query("""
            SELECT r.exchange_token, r.trading_symbol
              FROM upstox_bse_reference_equity r
              JOIN upstox_bse_reference_active a ON a.active_import = r.import_id
              JOIN upstox_bse_reference_import src ON src.id = a.active_import
             WHERE a.singleton_id=1
               AND src.source_fetched_at <= now()
               AND src.source_fetched_at >= now() - interval '36 hours'
            """, rs -> {
                java.util.Map<String,String> found=new java.util.HashMap<>();
                while(rs.next()) found.put(rs.getString(1),rs.getString(2));
                return Map.copyOf(found);
            });
    }

    public List<InstrumentMasterParser.InstrumentDraft> enrich(
            List<InstrumentMasterParser.InstrumentDraft> drafts) {
        Map<String,String> codes = recentVerifiedIdentities();
        if (codes.isEmpty()) return drafts;
        var output = new ArrayList<InstrumentMasterParser.InstrumentDraft>(drafts.size());
        for (var row : drafts) {
            boolean eligible = "BSE_CM".equals(row.exchangeSegment())
                && "BSE".equals(row.exchange())
                && (row.instrumentType()==null || row.instrumentType().isBlank())
                && row.symbolToken().matches("[0-9]{1,15}")
                && row.tradingSymbol()!=null
                && row.tradingSymbol().equalsIgnoreCase(codes.get(row.symbolToken()))
                && row.expiry()==null
                && (row.strike()==null || row.strike().signum()==0)
                && !row.tradingSymbol().contains("TEST");
            output.add(eligible ? new InstrumentMasterParser.InstrumentDraft(
                    row.exchangeSegment(),row.exchange(),row.symbolToken(),row.tradingSymbol(),
                    row.name(),"EQ",row.expiry(),row.strike(),row.lotSize(),row.tickSize()) : row);
        }
        return List.copyOf(output);
    }

    public Reconciliation auditActiveBrokerCatalogue() {
        return jdbc.query("""
            WITH raw AS (
                SELECT i.symbol_token, i.trading_symbol, i.instrument_type,
                       i.expiry, i.strike
                  FROM instrument_master i JOIN instrument_catalogue_state s
                    ON s.active_version=i.catalogue_version AND s.singleton_id=1
                 WHERE i.active AND i.exchange_segment='BSE_CM' AND i.exchange='BSE'
            ), src AS (
                SELECT q.exchange_token, q.trading_symbol
                  FROM upstox_bse_reference_equity q
                  JOIN upstox_bse_reference_active a ON a.active_import=q.import_id
                  JOIN upstox_bse_reference_import u ON u.id=a.active_import
                 WHERE a.singleton_id=1
                   AND u.source_fetched_at >= now()-interval '36 hours'
            )
            SELECT (SELECT COUNT(*) FROM raw),
                   (SELECT COUNT(*) FROM raw r JOIN src q
                     ON q.exchange_token=r.symbol_token
                    AND upper(q.trading_symbol)=upper(r.trading_symbol)
                    WHERE (r.instrument_type IS NULL OR r.instrument_type='')
                      AND r.expiry IS NULL AND (r.strike IS NULL OR r.strike=0)),
                   (SELECT COUNT(*) FROM raw r WHERE NOT EXISTS
                     (SELECT 1 FROM src q WHERE q.exchange_token=r.symbol_token
                       AND upper(q.trading_symbol)=upper(r.trading_symbol))),
                   (SELECT COUNT(*) FROM raw r JOIN src q
                     ON q.exchange_token=r.symbol_token
                    WHERE upper(q.trading_symbol)<>upper(r.trading_symbol)),
                   (SELECT COUNT(*) FROM src)
            """, (rs,row)->new Reconciliation(rs.getInt(1),rs.getInt(2),
                rs.getInt(3),rs.getInt(4),rs.getInt(5))).getFirst();
    }

    private static InstrumentMasterException invalid(String message) {
        return new InstrumentMasterException("UPSTOX_BSE_REFERENCE_INVALID",message);
    }
}
