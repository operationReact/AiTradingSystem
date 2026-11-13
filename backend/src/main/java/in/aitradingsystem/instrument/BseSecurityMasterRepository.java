package in.aitradingsystem.instrument;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable, source-dated and immutable official BSE cash-EQ reference data.
 * Publication is atomic. Broker records are not rewritten retroactively:
 * only a subsequent new Angel One catalogue publication can adopt proofs.
 */
@Repository
public class BseSecurityMasterRepository {
    private static final String SOURCE = "https://www.bseindia.com/downloads/Help/file/scrip.zip";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public record State(UUID version, LocalDate sourceAsOf, String sourceFile,
                        int sourceRecords, int verifiedEquities, String sha256,
                        java.time.Instant activatedAt) {}

    public BseSecurityMasterRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    public State current() {
        return jdbc.query("""
                SELECT a.active_import AS id, r.source_as_of, r.source_file,
                       r.total_records, r.verified_equities, r.archive_sha256, a.activated_at
                  FROM bse_security_master_active a
                  LEFT JOIN bse_security_master_import r ON r.id = a.active_import
                 WHERE a.singleton_id = 1
                """, (rs, index) -> new State(
            rs.getObject("id", UUID.class),
            rs.getObject("source_as_of", LocalDate.class),
            rs.getString("source_file"),
            rs.getInt("total_records"),
            rs.getInt("verified_equities"),
            rs.getString("archive_sha256"),
            rs.getObject("activated_at", OffsetDateTime.class) == null
                ? null : rs.getObject("activated_at", OffsetDateTime.class).toInstant()
        )).stream().findFirst().orElse(new State(null, null, null, 0, 0, null, null));
    }

    public State publish(BseSecurityMasterParser.Parsed parsed, String archiveSha) {
        if (parsed == null || parsed.verified().isEmpty()
                || archiveSha == null || !archiveSha.matches("[0-9a-f]{64}")) {
            throw new InstrumentMasterException("BSE_SOURCE_INVALID", "Invalid BSE security-master publication");
        }
        return transaction.execute(status -> {
            // Lock the singleton to serialize competing runs and prevent source downgrade.
            List<UUID> current = jdbc.query("""
                SELECT active_import FROM bse_security_master_active
                 WHERE singleton_id = 1 FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class));
            if (current.isEmpty()) {
                throw new InstrumentMasterException("BSE_SOURCE_UNAVAILABLE",
                    "BSE security-master publication state missing");
            }
            State active = current();
            if (active.version() != null && archiveSha.equals(active.sha256())) return active;
            if (active.sourceAsOf() != null && parsed.asOf().isBefore(active.sourceAsOf())) {
                throw new InstrumentMasterException("BSE_SOURCE_STALE",
                    "An older BSE source cannot replace the current verified security master");
            }
            UUID version = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO bse_security_master_import
                    (id, source_url, source_file, source_as_of,
                     archive_sha256, total_records, verified_equities)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, version, SOURCE, parsed.sourceFile(), parsed.asOf(),
                archiveSha, parsed.sourceRecords(), parsed.verified().size());
            for (int offset = 0; offset < parsed.verified().size(); offset += 500) {
                List<BseSecurityMasterParser.Equity> chunk =
                    parsed.verified().subList(offset, Math.min(offset + 500, parsed.verified().size()));
                jdbc.batchUpdate("""
                    INSERT INTO bse_verified_cash_equity
                        (import_id, scrip_code, security_id, isin)
                    VALUES (?, ?, ?, ?)
                    """, chunk, chunk.size(), (PreparedStatement stmt,
                        BseSecurityMasterParser.Equity e) -> {
                        stmt.setObject(1, version);
                        stmt.setString(2, e.scripCode());
                        stmt.setString(3, e.securityId());
                        stmt.setString(4, e.isin());
                    });
            }
            jdbc.update("""
                UPDATE bse_security_master_active
                   SET active_import = ?, activated_at = now()
                 WHERE singleton_id = 1
                """, version);
            return current();
        });
    }

    /** Eligible codes ONLY while the reference is fresh. Unknown codes fail closed. */
    public Set<String> recentVerifiedEquityCodes() {
        return new HashSet<>(jdbc.query("""
            SELECT r.scrip_code
              FROM bse_verified_cash_equity r
              JOIN bse_security_master_active a ON a.active_import = r.import_id
              JOIN bse_security_master_import src ON src.id = a.active_import
             WHERE a.singleton_id = 1
               AND src.source_as_of <= (now() AT TIME ZONE 'Asia/Kolkata')::date
               AND src.source_as_of >= (now() AT TIME ZONE 'Asia/Kolkata')::date - 10
            """, (rs, row) -> rs.getString(1)));
    }

    /**
     * A new broker catalogue can promote an untyped BSE cash instrument only on
     * exact BSE numeric scrip-code proof from a recent official security master.
     * We never mutate old published catalogues or past research snapshots.
     */
    public List<InstrumentMasterParser.InstrumentDraft> enrichForNewCatalogue(
            List<InstrumentMasterParser.InstrumentDraft> drafts) {
        Set<String> verifiedCodes = recentVerifiedEquityCodes();
        if (verifiedCodes.isEmpty()) return drafts;
        List<InstrumentMasterParser.InstrumentDraft> enriched = new ArrayList<>(drafts.size());
        for (var d : drafts) {
            boolean canPromote = "BSE".equals(d.exchange())
                && "BSE_CM".equals(d.exchangeSegment())
                && (d.instrumentType() == null || d.instrumentType().isBlank())
                && d.symbolToken().matches("[0-9]{1,15}")
                && verifiedCodes.contains(d.symbolToken())
                && d.expiry() == null
                && (d.strike() == null || d.strike().signum() == 0)
                && d.tradingSymbol() != null
                && !d.tradingSymbol().contains("TEST");
            enriched.add(canPromote
                ? new InstrumentMasterParser.InstrumentDraft(
                    d.exchangeSegment(), d.exchange(), d.symbolToken(), d.tradingSymbol(),
                    d.name(), "EQ", d.expiry(), d.strike(), d.lotSize(), d.tickSize())
                : d);
        }
        return List.copyOf(enriched);
    }
}
