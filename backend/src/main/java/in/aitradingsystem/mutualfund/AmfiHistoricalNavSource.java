package in.aitradingsystem.mutualfund;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * AMFI official historical NAV export. Format differs from daily NAVAll.txt.
 * Fetches one week per operation, never scrapes fund websites or submits orders.
 */
@Component
public class AmfiHistoricalNavSource {
    private static final String BASE = "https://portal.amfiindia.com/DownloadNAVHistoryReport_Po.aspx";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ENGLISH);
    private static final int MAX_BYTES = 12_000_000;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public record Nav(int schemeCode,LocalDate navDate,BigDecimal nav) {}

    public List<Nav> fetch(LocalDate from, LocalDate through) {
        validateRange(from, through);
        String query = "frmdt=" + URLEncoder.encode(DATE.format(from),StandardCharsets.UTF_8)
            + "&todt=" + URLEncoder.encode(DATE.format(through),StandardCharsets.UTF_8);
        URI uri = URI.create(BASE + "?" + query);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(90))
                .header("Accept","text/plain")
                .header("User-Agent","MintTick/1.0 (mutual-fund NAV research)")
                .GET().build();
            HttpResponse<byte[]> response = http.send(request,HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null
                    || response.body().length < 100 || response.body().length > MAX_BYTES) {
                throw new IllegalStateException("AMFI_HISTORY_UNAVAILABLE");
            }
            return parse(new String(response.body(),StandardCharsets.UTF_8),from,through);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AMFI_HISTORY_INTERRUPTED",interrupted);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("AMFI_HISTORY_NETWORK_FAILED",failure);
        }
    }

    public static List<Nav> parse(String text,LocalDate from,LocalDate through) {
        validateRange(from,through);
        if (text == null || text.length() < 100 || text.length() > MAX_BYTES
                || !text.startsWith("Scheme Code;Scheme Name;")) {
            throw new IllegalArgumentException("AMFI_HISTORY_INVALID_HEADER");
        }
        Map<String,Nav> records = new HashMap<>();
        Map<Integer,Integer> coverage = new HashMap<>();
        for (String line : text.split("\\R")) {
            String[] row = line.split(";",-1);
            if (row.length != 8 || !row[0].matches("[0-9]{5,7}")) continue;
            int code = Integer.parseInt(row[0]);
            MfResearchCatalogue.Fund fund = MfResearchCatalogue.BY_CODE.get(code);
            if (fund == null) continue;
            // Official history has no separate plan/option fields; ISIN + scheme
            // code is the immutable direct-growth identity instead.
            if (!fund.isin().equals(row[2].trim())) {
                throw new IllegalArgumentException("AMFI_HISTORY_SCHEME_MISMATCH");
            }
            LocalDate date;
            BigDecimal nav;
            try {
                date = LocalDate.parse(row[7].trim(),DATE);
                nav = new BigDecimal(row[4].trim());
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("AMFI_HISTORY_INVALID_VALUE",invalid);
            }
            if (date.isBefore(from) || date.isAfter(through)
                    || nav.signum() <= 0 || nav.scale() > 6) {
                throw new IllegalArgumentException("AMFI_HISTORY_INVALID_VALUE");
            }
            String key = code+"|"+date;
            if (records.putIfAbsent(key,new Nav(code,date,nav)) != null)
                throw new IllegalArgumentException("AMFI_HISTORY_DUPLICATE");
            coverage.merge(code,1,Integer::sum);
        }
        if (coverage.size() != MfResearchCatalogue.FUNDS.size()) {
            throw new IllegalArgumentException("AMFI_HISTORY_INCOMPLETE");
        }
        // Preserve only dates with complete nine-scheme coverage. Missing
        // AMC NAVs do not turn into zero NAVs or manufactured observations.
        Map<LocalDate,Set<Integer>> perDate = new HashMap<>();
        for (Nav nav: records.values()) {
            perDate.computeIfAbsent(nav.navDate(),ignored->new HashSet<>()).add(nav.schemeCode());
        }
        List<Nav> accepted = records.values().stream()
            .filter(nav->perDate.get(nav.navDate()).size() == MfResearchCatalogue.FUNDS.size())
            .sorted(java.util.Comparator.comparing(Nav::navDate).thenComparingInt(Nav::schemeCode))
            .toList();
        if (accepted.size() < MfResearchCatalogue.FUNDS.size()) {
            throw new IllegalArgumentException("AMFI_HISTORY_NO_COMPLETE_SESSIONS");
        }
        return accepted;
    }

    private static void validateRange(LocalDate from,LocalDate through) {
        if (from == null || through == null || through.isBefore(from)
            || through.isAfter(from.plusDays(6))) {
            throw new IllegalArgumentException("AMFI_HISTORY_RANGE_INVALID");
        }
    }
}
