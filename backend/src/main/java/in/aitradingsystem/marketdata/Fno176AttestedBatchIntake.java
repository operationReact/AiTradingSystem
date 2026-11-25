package in.aitradingsystem.marketdata;

import java.security.PublicKey;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Private composition entry point. No HTTP or scheduled invocation and no
 * source permissions are created here. A trusted reviewer key must be pinned
 * independently of the source bundle. Each row goes through the existing
 * rights/calendar/append-only database gate and is counted only on insert.
 */
public final class Fno176AttestedBatchIntake {
    private static final int MAX_BATCH=32;
    private final NamedParameterJdbcTemplate jdbc;
    private final GoldenSourceRightsRepository rights;
    private final PublicKey reviewer;
    private final Clock clock;

    public record Entry(Fno176OriginalObservedIngestor.OriginalPackets packets,
                        Fno176AttestedSourceVerifier.Receipt receipt) {}
    public record Result(int submitted,int stored,int rejected,
                         boolean independentlyHistoricalE2ECertified,
                         boolean ordersPlaced) {}

    public Fno176AttestedBatchIntake(NamedParameterJdbcTemplate jdbc,
            GoldenSourceRightsRepository rights,PublicKey reviewer,Clock clock) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.rights=Objects.requireNonNull(rights);
        this.reviewer=Objects.requireNonNull(reviewer);
        this.clock=Objects.requireNonNull(clock);
    }

    public Result ingest(List<Entry> entries) {
        if(entries==null || entries.isEmpty() || entries.size()>MAX_BATCH)
            throw new IllegalArgumentException("SOURCE_BATCH_UNBOUNDED");
        // Reject malformed or duplicate identities before any write.
        var seen=new HashSet<List<String>>();
        for(var entry:entries) {
            if(entry==null || entry.packets()==null || entry.receipt()==null
                    || entry.receipt().day()==null)
                throw new IllegalArgumentException("SOURCE_BATCH_INVALID");
            var d=entry.receipt().day();
            if(d.providerCode()==null || d.instrumentKey()==null || d.sessionDate()==null
                    || !seen.add(List.of(d.providerCode(),d.instrumentKey(),d.sessionDate().toString())))
                throw new IllegalArgumentException("SOURCE_BATCH_DUPLICATE_OR_INVALID");
        }
        int stored=0;
        for(var entry:entries) {
            var verifier=new Fno176AttestedSourceVerifier(reviewer,clock,entry.receipt());
            var result=new Fno176OriginalObservedIngestor(jdbc,rights,verifier,clock)
                    .ingest(entry.packets());
            if(result.originalSessionStored()) stored++;
        }
        return new Result(entries.size(),stored,entries.size()-stored,false,false);
    }
}
