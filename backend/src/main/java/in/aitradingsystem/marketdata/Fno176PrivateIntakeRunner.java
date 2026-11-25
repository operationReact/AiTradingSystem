package in.aitradingsystem.marketdata;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Explicitly enabled private startup import. Does not expose HTTP routes,
 * fetch broker data or alter trading configuration. Foundation rights and
 * calendar rows can be appended only from a separately signed reviewer packet.
 * The operator supplies an independently pinned public key and a read-only
 * private source directory. Source packets and receipt details never escape
 * into logs. Market rows still pass through the existing V102 boundary.
 */
@Component
@ConditionalOnProperty(name="minttick.fno-research.private-intake-enabled",havingValue="true")
public final class Fno176PrivateIntakeRunner implements ApplicationRunner {
    private static final Logger LOG=LoggerFactory.getLogger(Fno176PrivateIntakeRunner.class);
    private static final int MAX_ENTRIES=32;
    private static final int MIB=1024*1024;
    private static final int MAX_FOUNDATION_BYTES=64*1024;
    private static final Set<PosixFilePermission> PRIVATE=Set.of(
        PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final NamedParameterJdbcTemplate jdbc;
    private final GoldenSourceRightsRepository rights;
    private final String directory;
    private final String encodedPublicKey;

    public Fno176PrivateIntakeRunner(NamedParameterJdbcTemplate jdbc,
            GoldenSourceRightsRepository rights,
            @Value("${minttick.fno-research.private-intake-directory:}") String directory,
            @Value("${minttick.fno-research.private-intake-reviewer-public-key:}") String encodedPublicKey) {
        this.jdbc=jdbc;
        this.rights=rights;
        this.directory=directory;
        this.encodedPublicKey=encodedPublicKey;
    }

    record Report(String state,int submitted,int stored,int rejected) {}

    @Override public void run(ApplicationArguments args) {
        var result=importOnce();
        LOG.info("FNO176_PRIVATE_INTAKE state={} submitted={} stored={} rejected={} certified=false orders=false",
            result.state(),result.submitted(),result.stored(),result.rejected());
    }

    Report importOnce() {
        try {
            if(directory==null || directory.isBlank() || encodedPublicKey==null
                    || encodedPublicKey.isBlank() || encodedPublicKey.length()>256)
                return new Report("CONFIGURATION_MISSING",0,0,0);
            Path root=Path.of(directory);
            checkPrivateDirectory(root);
            PublicKey reviewer=KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(encodedPublicKey)));
            List<Path> children;
            try(var paths=Files.list(root)) {
                children=new ArrayList<>(paths.limit(MAX_ENTRIES+2L).sorted().toList());
            }
            if(children.size()>MAX_ENTRIES+1)
                return new Report("SOURCE_BATCH_UNBOUNDED",0,0,0);
            Path foundationPath=root.resolve("foundation.json");
            boolean hasFoundation=children.remove(foundationPath);
            if(children.isEmpty() && !hasFoundation)
                return new Report("NO_SOURCE_BUNDLES",0,0,0);
            if(children.size()>MAX_ENTRIES)
                return new Report("SOURCE_BATCH_UNBOUNDED",0,0,0);
            // Validate the complete directory structure before any writes.
            for(var entry:children) {
                if(!entry.getFileName().toString().matches("entry-[0-9]{4}"))
                    return new Report("SOURCE_BATCH_INVALID",0,0,0);
                checkPrivateDirectory(entry);
            }
            Fno176AttestedFoundationIntake.Receipt foundation=null;
            if(hasFoundation) {
                byte[] bytes=read(root,"foundation.json",MAX_FOUNDATION_BYTES);
                foundation=Fno176AttestedFoundationIntake.read(bytes);
                var sessions=new HashSet<LocalDate>();
                for(var entry:children) {
                    var receipt=JSON.readValue(read(entry,"receipt.json",16384),
                        Fno176AttestedSourceVerifier.Receipt.class);
                    var day=receipt.day();
                    if(day==null || !day.providerCode().equals(foundation.providerCode())
                            || !foundation.rights().stream().anyMatch(g -> g.receiptId().equals(day.ohlcvInferenceRightsId())
                                && g.sourceDomain().equals("nse_fo_completed_ohlcv")
                                && g.permittedUse().equals("MODEL_INFERENCE"))
                            || !foundation.rights().stream().anyMatch(g -> g.receiptId().equals(day.oiInferenceRightsId())
                                && g.sourceDomain().equals("nse_fo_final_oi")
                                && g.permittedUse().equals("MODEL_INFERENCE"))
                            || !foundation.calendar().stream().anyMatch(c -> c.evidenceId().equals(day.calendarEvidenceId())
                                && c.sessionDate().equals(day.sessionDate()) && c.sessionStatus().equals("TRADING")))
                        return new Report("FOUNDATION_PACKET_MISMATCH",children.size(),0,children.size());
                    sessions.add(day.sessionDate());
                }
                sessions.addAll(foundation.calendar().stream().map(
                    Fno176AttestedFoundationIntake.CalendarRecord::sessionDate).toList());
                new Fno176AttestedFoundationIntake(jdbc,reviewer,Clock.systemUTC())
                    .append(foundation,sessions);
            }
            if(children.isEmpty())
                return new Report("FOUNDATION_STORED_NOT_E2E",0,0,0);
            int stored=0;
            var intake=new Fno176AttestedBatchIntake(jdbc,rights,reviewer,Clock.systemUTC());
            for(var entry:children) {
                // Process one entry at a time to bound memory even for masters.
                try {
                    var receipt=JSON.readValue(read(entry,"receipt.json",16384),
                        Fno176AttestedSourceVerifier.Receipt.class);
                    if(receipt.day()==null) continue;
                    var packets=new Fno176OriginalObservedIngestor.OriginalPackets(
                        receipt.day().providerCode(),read(entry,"ohlcv.bin",MIB),
                        read(entry,"oi.bin",MIB),read(entry,"listing.bin",20*MIB));
                    var result=intake.ingest(List.of(
                        new Fno176AttestedBatchIntake.Entry(packets,receipt)));
                    stored+=result.stored();
                } catch(IOException | RuntimeException rejected) {
                    // Sanitized aggregate only. No source details or stack traces.
                }
            }
            return new Report(stored==children.size()?"OBSERVATIONS_STORED_NOT_E2E":
                stored==0?"ALL_OBSERVATIONS_REJECTED":"PARTIAL_OBSERVATIONS_STORED_NOT_E2E",
                children.size(),stored,children.size()-stored);
        } catch(IOException | java.security.GeneralSecurityException | RuntimeException rejected) {
            return new Report("PRIVATE_INTAKE_UNAVAILABLE",0,0,0);
        }
    }

    private static void checkPrivateDirectory(Path path) throws IOException {
        if(!path.isAbsolute() || !path.equals(path.normalize()) || !path.equals(path.toRealPath())
                || !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)
                || !PRIVATE.containsAll(Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("PRIVATE_DIRECTORY_REQUIRED");
    }

    private static byte[] read(Path dir,String name,int max) throws IOException {
        Path path=dir.resolve(name);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                || !PRIVATE.containsAll(Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS))
                || Files.size(path)<1 || Files.size(path)>max)
            throw new IOException("PRIVATE_SOURCE_FILE_INVALID");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] result=input.readNBytes(max+1);
            if(result.length<1 || result.length>max) throw new IOException("SOURCE_FILE_BOUND");
            return result;
        }
    }
}
