package in.aitradingsystem.instrument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/**
 * Official BSE Equity Scrip Master: BSE_EQ_SCRIP_DDMMYYYY.csv inside scrip.zip.
 * Uses the BSE documented positional fields: code (1), security id (2), ISIN (5),
 * security type EQ (8), trading status A (60), and instrument type E (64).
 *
 * Fail-closed: no company-name matching, no MF/debt promotion and no inferred
 * eligibility from a settlement group, a broker suffix or a missing source flag.
 */
@Component
public final class BseSecurityMasterParser {
    private static final Pattern FILENAME =
        Pattern.compile("BSE_EQ_SCRIP_([0-9]{8})\\.CSV", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("ddMMuuuu", Locale.ROOT);
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");
    private static final Pattern CODE = Pattern.compile("[0-9]{1,15}");
    private static final int MAX_CSV_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ROWS = 80_000;
    private static final int MAX_FIELDS = 120;

    public record Equity(String scripCode, String securityId, String isin) {}
    public record Parsed(String sourceFile, LocalDate asOf, int sourceRecords, List<Equity> verified) {}

    public Parsed parseZip(byte[] archive, LocalDate today, int minimumVerified) {
        if (archive == null || archive.length < 8 || archive.length > 25 * 1024 * 1024
                || today == null || minimumVerified < 1) {
            throw invalid("Invalid bounded official BSE security-master archive");
        }
        try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            Parsed result = null;
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 100 || entry.getName().contains("..") ||
                        entry.getName().startsWith("/") || entry.getName().contains("\\") ||
                        entry.isDirectory()) {
                    throw invalid("Unexpected BSE security-master archive entry");
                }
                String name = entry.getName();
                var match = FILENAME.matcher(name);
                if (match.matches()) {
                    if (result != null) throw invalid("Ambiguous BSE equity source files");
                    LocalDate asOf = date(match.group(1), today);
                    byte[] csv = readBounded(zip);
                    result = parseCsv(name, asOf, csv);
                }
                zip.closeEntry();
            }
            if (result == null || result.verified().size() < minimumVerified) {
                throw invalid("Complete dated official BSE equity security master was not available");
            }
            return result;
        } catch (IOException e) {
            throw invalid("BSE security-master archive could not be verified");
        }
    }

    static Parsed parseCsv(String name, LocalDate asOf, byte[] bytes) {
        // The security and status flags are ASCII. ISO-8859-1 preserves every byte,
        // including legacy company names, without silently dropping invalid UTF-8.
        String csv = new String(bytes, StandardCharsets.ISO_8859_1);
        if (!csv.isEmpty() && csv.charAt(0) == '\uFEFF') csv = csv.substring(1);
        Map<String, Equity> equities = new HashMap<>();
        int records = 0;
        int skippedHeaders = 0;
        for (String line : csv.split("\\r?\\n", -1)) {
            if (line.isBlank()) continue;
            if (line.length() > 12_000 || ++records > MAX_ROWS) {
                throw invalid("Official BSE equity security master exceeds row limits");
            }
            List<String> fields = splitCsv(line);
            if (records == 1 && !fields.isEmpty()
                    && (fields.getFirst().equalsIgnoreCase("Scrip Code")
                        || fields.getFirst().equalsIgnoreCase("SC_CODE"))) {
                skippedHeaders++;
                continue;
            }
            if (fields.size() < 64) {
                throw invalid("Official BSE security master is missing required status columns");
            }
            String code = fields.get(0).trim();
            if (!CODE.matcher(code).matches()) {
                throw invalid("Invalid official BSE numeric security code");
            }
            String type = fields.get(7).trim();
            String status = fields.get(59).trim();
            String instrument = fields.get(63).trim();
            // Explicit exchange flags, never settlement-group or name inference.
            if (!"EQ".equals(type) || !"A".equals(status) || !"E".equals(instrument)) continue;
            String isin = fields.get(4).trim().toUpperCase(Locale.ROOT);
            String securityId = fields.get(1).trim().toUpperCase(Locale.ROOT);
            if (!ISIN.matcher(isin).matches() || securityId.isEmpty() ||
                    securityId.length() > 20 || !securityId.matches("[A-Z0-9&._/+-]+")) {
                throw invalid("Official active BSE equity identity was invalid");
            }
            var item = new Equity(code, securityId, isin);
            if (equities.putIfAbsent(code, item) != null) {
                throw invalid("Duplicate BSE security codes in one official master");
            }
        }
        if (records - skippedHeaders <= 0 || equities.isEmpty()) {
            throw invalid("No verified active BSE cash equities");
        }
        return new Parsed(name, asOf, records - skippedHeaders,
            List.copyOf(equities.values().stream()
                .sorted(java.util.Comparator.comparing(Equity::scripCode)).toList()));
    }

    private static List<String> splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        var value = new StringBuilder();
        boolean quoted = false;
        boolean afterQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        value.append('"');
                        i++;
                    } else {
                        quoted = false;
                        afterQuote = true;
                    }
                } else {
                    value.append(c);
                }
            } else if (c == ',' && !quoted) {
                fields.add(value.toString());
                if (fields.size() > MAX_FIELDS) throw invalid("BSE CSV has too many columns");
                value.setLength(0);
                afterQuote = false;
            } else if (c == '"' && value.isEmpty() && !afterQuote) {
                quoted = true;
            } else if (afterQuote && c != ' ' && c != '\t') {
                throw invalid("Invalid quoting in official BSE CSV");
            } else if (!afterQuote) {
                value.append(c);
            }
        }
        if (quoted) throw invalid("Unclosed quoted field in BSE CSV");
        fields.add(value.toString());
        if (fields.size() > MAX_FIELDS) throw invalid("BSE CSV has too many columns");
        return fields;
    }

    private static LocalDate date(String raw, LocalDate today) {
        try {
            LocalDate date = LocalDate.parse(raw, DATE);
            if (date.isAfter(today) || date.isBefore(today.minusDays(10))) {
                throw invalid("Official BSE security master is stale or future-dated");
            }
            return date;
        } catch (DateTimeParseException e) {
            throw invalid("Official BSE security master has an invalid date");
        }
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        var result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (count > MAX_CSV_BYTES - result.size()) {
                throw invalid("Decompressed BSE CSV exceeds the size limit");
            }
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }

    private static InstrumentMasterException invalid(String message) {
        return new InstrumentMasterException("BSE_SOURCE_INVALID", message);
    }
}
