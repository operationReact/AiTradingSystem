package in.aitradingsystem.instrument;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Secondary Upstox public BSE BOD JSON.gz is not the BSE exchange's official
 * master. Refresh is an explicit operator action, never a customer OAuth step.
 * No orders, personal credentials or broker callbacks are touched.
 */
@Service
public class UpstoxBseCrosswalkService {
    static final URI SOURCE =
        URI.create("https://assets.upstox.com/market-quote/instruments/exchange/BSE.json.gz");
    private final UpstoxBseCrosswalkParser parser;
    private final UpstoxBseCrosswalkRepository repository;
    private final InstrumentMasterService importer;

    public record Result(UpstoxBseCrosswalkRepository.Status source,
                         UpstoxBseCrosswalkRepository.Reconciliation reconciliation,
                         InstrumentImportSummary brokerCatalogue) {}

    public UpstoxBseCrosswalkService(UpstoxBseCrosswalkParser parser,
            UpstoxBseCrosswalkRepository repository, InstrumentMasterService importer) {
        this.parser=parser;
        this.repository=repository;
        this.importer=importer;
    }

    public synchronized Result refresh() {
        byte[] compressed=download();
        var verified=parser.parseGzip(compressed);
        String checksum=checksum(compressed);
        var published=repository.publish(verified,checksum,Instant.now());
        // Atomically publishing a NEW broker catalogue applies only matched
        // source-custodied cross-provider identities; no old versions are mutated.
        var broker=importer.importLatest();
        return new Result(published,repository.auditActiveBrokerCatalogue(),broker);
    }

    public UpstoxBseCrosswalkRepository.Status status() { return repository.current(); }
    public UpstoxBseCrosswalkRepository.Reconciliation audit() {
        return repository.auditActiveBrokerCatalogue();
    }

    private static byte[] download() {
        try {
            HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest request=HttpRequest.newBuilder(SOURCE).timeout(Duration.ofSeconds(45))
                .header("Accept","application/gzip,application/octet-stream").GET().build();
            HttpResponse<InputStream> response=client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream=response.body()) {
                if (response.statusCode()!=200) throw new InstrumentMasterException(
                    "UPSTOX_BSE_SOURCE_UNAVAILABLE","Upstox public BSE JSON.gz was not accessible");
                var out=new ByteArrayOutputStream();
                var bytes=new byte[8192];
                int n;
                while((n=stream.read(bytes))>=0) {
                    if(n>32*1024*1024-out.size()) throw new InstrumentMasterException(
                        "UPSTOX_BSE_SOURCE_TOO_LARGE","Upstox BSE archive exceeds bounded source size");
                    out.write(bytes,0,n);
                }
                return out.toByteArray();
            }
        } catch (InstrumentMasterException e) { throw e; }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InstrumentMasterException("UPSTOX_BSE_SOURCE_UNAVAILABLE",
                "Upstox BSE fetch interrupted");
          } catch (Exception e) {
            throw new InstrumentMasterException("UPSTOX_BSE_SOURCE_UNAVAILABLE",
                "Upstox BSE source was not accessible");
          }
    }

    private static String checksum(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required by JVM",e);
        }
    }
}

@RestController
@RequestMapping("/api/instruments/upstox-bse-crosswalk")
class UpstoxBseCrosswalkController {
    private final UpstoxBseCrosswalkService service;
    UpstoxBseCrosswalkController(UpstoxBseCrosswalkService service) {this.service=service;}

    @GetMapping("/status")
    ResponseEntity<UpstoxBseCrosswalkRepository.Status> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status());
    }
    @GetMapping("/reconciliation")
    ResponseEntity<UpstoxBseCrosswalkRepository.Reconciliation> audit() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.audit());
    }
    /** Generic Spring Security /api/** POST policy restricts to ADMIN/OPERATOR. */
    @PostMapping("/refresh")
    ResponseEntity<UpstoxBseCrosswalkService.Result> refresh() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.refresh());
    }
}
