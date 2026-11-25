package in.aitradingsystem.instrument;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Operator-controlled refresh of the BSE-published SCRIP.ZIP. No third-party
 * mirrors, unchecked URLs or in-app user uploads are accepted as exchange proof.
 * Disabled by default until source access is verified in the deployment.
 */
@Service
public class BseSecurityMasterService {
    private static final Logger log = LoggerFactory.getLogger(BseSecurityMasterService.class);
    static final URI SOURCE = URI.create("https://www.bseindia.com/downloads/Help/file/scrip.zip");
    private static final int MAX_ARCHIVE_BYTES = 25 * 1024 * 1024;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final BseSecurityMasterParser parser;
    private final BseSecurityMasterRepository repository;
    private final InstrumentMasterService instruments;
    private final HttpClient client;

    public record Refresh(BseSecurityMasterRepository.State verifiedReference,
                          InstrumentImportSummary brokerCatalogueImport) {}

    @org.springframework.beans.factory.annotation.Autowired
    public BseSecurityMasterService(BseSecurityMasterParser parser,
            BseSecurityMasterRepository repository, InstrumentMasterService instruments) {
        this(parser, repository, instruments, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    BseSecurityMasterService(BseSecurityMasterParser parser,
            BseSecurityMasterRepository repository, InstrumentMasterService instruments,
            HttpClient client) {
        this.parser = parser;
        this.repository = repository;
        this.instruments = instruments;
        this.client = client;
    }

    public BseSecurityMasterRepository.State status() {
        return repository.current();
    }

    public synchronized Refresh refresh() {
        byte[] archive = download();
        LocalDate today = LocalDate.now(IST);
        var parsed = parser.parseZip(archive, today, 100);
        String sha256 = checksum(archive);
        BseSecurityMasterRepository.State verified = repository.publish(parsed, sha256);
        // Only a NEW version of the broker catalogue can include newly verified
        // EQ rows, so historical research evidence is never retroactively changed.
        InstrumentImportSummary brokerImport = instruments.importLatest();
        return new Refresh(verified, brokerImport);
    }

    /** Set an approved cron explicitly after confirming member/public source access. */
    @Scheduled(cron = "${minttick.bse-security-master.cron:-}", zone = "Asia/Kolkata")
    public void scheduledRefresh() {
        try {
            Refresh refresh = refresh();
            log.info("BSE security-master refresh completed: verified={}, brokerImport={}",
                refresh.verifiedReference().verifiedEquities(),
                refresh.brokerCatalogueImport().status());
        } catch (RuntimeException e) {
            // No URLs with credentials, response bodies, scrip master contents or tokens.
            log.warn("BSE security-master refresh not applied: {}", e.getClass().getSimpleName());
        }
    }

    byte[] download() {
        try {
            HttpRequest request = HttpRequest.newBuilder(SOURCE)
                .timeout(Duration.ofSeconds(80))
                .header("Accept", "application/zip, application/octet-stream")
                .GET().build();
            HttpResponse<InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new InstrumentMasterException("BSE_SOURCE_UNAVAILABLE",
                        "Official BSE scrip ZIP could not be downloaded (HTTP "
                            + response.statusCode() + ")");
                }
                var result = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (count > MAX_ARCHIVE_BYTES - result.size()) {
                        throw new InstrumentMasterException("BSE_SOURCE_TOO_LARGE",
                            "Official BSE source exceeded archive size limits");
                    }
                    result.write(buffer, 0, count);
                }
                byte[] bytes = result.toByteArray();
                if (bytes.length < 8 || bytes[0] != 'P' || bytes[1] != 'K') {
                    throw new InstrumentMasterException("BSE_SOURCE_INVALID",
                        "Official BSE source was not a ZIP archive");
                }
                return bytes;
            }
        } catch (InstrumentMasterException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InstrumentMasterException("BSE_SOURCE_UNAVAILABLE",
                "BSE security-master download interrupted");
        } catch (Exception e) {
            throw new InstrumentMasterException("BSE_SOURCE_UNAVAILABLE",
                "BSE security-master source is unreachable");
        }
    }

    static String checksum(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
