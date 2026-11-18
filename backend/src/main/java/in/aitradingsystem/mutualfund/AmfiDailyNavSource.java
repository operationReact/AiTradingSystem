package in.aitradingsystem.mutualfund;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Verified, allowlisted public AMFI daily NAV source, NEVER an equity LTP feed.
 * Strictly matches scheme code, ISIN, scheme, Direct Plan and Growth option.
 */
@Component
public class AmfiDailyNavSource {
    public record DailyNav(int schemeCode, LocalDate date, BigDecimal nav) {}
    private static final URI SOURCE = URI.create("https://portal.amfiindia.com/spages/NAVAll.txt");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ENGLISH);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final int MAX_BYTES = 8_000_000;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public Map<Integer, DailyNav> fetch() {
        try {
            HttpRequest request = HttpRequest.newBuilder(SOURCE)
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "text/plain")
                .header("User-Agent", "MintTick/1.0 (mutual-fund NAV public research)")
                .GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null
                    || response.body().length == 0 || response.body().length > MAX_BYTES) {
                throw new IllegalStateException("AMFI_RESPONSE_UNAVAILABLE");
            }
            return parse(new String(response.body(), StandardCharsets.UTF_8), LocalDate.now(INDIA));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AMFI_FETCH_INTERRUPTED", interrupted);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("AMFI_FETCH_UNAVAILABLE", failure);
        }
    }

    public static Map<Integer, DailyNav> parse(String source, LocalDate today) {
        if (source == null || source.length() < 100 || source.length() > MAX_BYTES
                || !source.startsWith("Scheme Code;")) {
            throw new IllegalArgumentException("AMFI_INVALID_HEADER");
        }
        Map<Integer, DailyNav> found = new HashMap<>();
        for (String line : source.split("\\R")) {
            String[] row = line.split(";", -1);
            if (row.length != 8 || !row[0].matches("[0-9]{5,7}")) continue;
            int code = Integer.parseInt(row[0]);
            MfResearchCatalogue.Fund fund = MfResearchCatalogue.BY_CODE.get(code);
            if (fund == null) continue;
            if (!fund.isin().equals(row[1]) || !fund.name().equalsIgnoreCase(row[3].trim())
                || !"Direct Plan".equalsIgnoreCase(row[4].trim())
                || !row[5].toLowerCase(Locale.ROOT).startsWith("growth")
                || !row[2].trim().equals("-")) {
                throw new IllegalArgumentException("AMFI_SCHEME_IDENTITY_MISMATCH");
            }
            BigDecimal nav;
            LocalDate date;
            try {
                nav = new BigDecimal(row[6].trim());
                date = LocalDate.parse(row[7].trim(), DATE);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("AMFI_NAV_VALUE_INVALID", invalid);
            }
            if (nav.signum() <= 0 || nav.scale() > 6
                || date.isAfter(today) || date.isBefore(today.minusDays(7))) {
                throw new IllegalArgumentException("AMFI_NAV_STALE_OR_INVALID");
            }
            DailyNav previous = found.putIfAbsent(code, new DailyNav(code, date, nav));
            if (previous != null) throw new IllegalArgumentException("AMFI_DUPLICATE_SCHEME");
        }
        if (found.size() != MfResearchCatalogue.FUNDS.size()) {
            throw new IllegalArgumentException("AMFI_WATCHLIST_INCOMPLETE");
        }
        return Map.copyOf(found);
    }
}
