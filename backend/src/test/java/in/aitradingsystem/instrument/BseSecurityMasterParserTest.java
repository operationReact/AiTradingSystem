package in.aitradingsystem.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class BseSecurityMasterParserTest {
    private final BseSecurityMasterParser parser = new BseSecurityMasterParser();
    private final LocalDate date = LocalDate.of(2026, 11, 13);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("ddMMuuuu");

    @Test
    void onlyActiveEqAndEquityInstrumentRowsCanBecomeCashEquities() throws Exception {
        String csv = String.join("\n",
            row("500325", "RELIANCE", "INE002A01018", "EQ", "A", "E"),
            row("532540", "TCS", "INE467B01029", "EQ", "A", "E"),
            row("500328", "TESTFUND", "INF000000001", "MF", "A", "E"),
            row("500329", "SUSPENDED", "INE000000001", "EQ", "S", "E"),
            row("500330", "DEBT", "INE000000002", "DB", "A", "D"),
            row("500331", "CLEANDEBT", "INE000000003", "EQ", "A", "C"));
        var source = parser.parseZip(zip(file(date), csv), date, 2);
        assertThat(source.sourceFile()).isEqualTo(file(date));
        assertThat(source.asOf()).isEqualTo(date);
        assertThat(source.sourceRecords()).isEqualTo(6);
        assertThat(source.verified()).extracting(BseSecurityMasterParser.Equity::scripCode)
            .containsExactly("500325", "532540");
        assertThat(source.verified().getFirst().isin()).isEqualTo("INE002A01018");
    }

    @Test
    void quoteDelimitedNamesContainingCommasCannotShiftTypeOrStatusColumns() throws Exception {
        String csv = row("500325", "RELIANCE", "INE002A01018", "EQ", "A", "E");
        csv = csv.replace("SCRIPNAME", "\"A, B \"\"C\"\" INDUSTRIES\"");
        var parsed = parser.parseZip(zip(file(date), csv), date, 1);
        assertThat(parsed.verified()).hasSize(1);
    }

    @Test
    void noPromotionWhenSourceIsStaleFutureOrHasWrongFileName() throws Exception {
        String csv = row("500325", "RELIANCE", "INE002A01018", "EQ", "A", "E");
        assertThatThrownBy(() -> parser.parseZip(zip(file(date.minusDays(11)), csv), date, 1))
            .isInstanceOf(InstrumentMasterException.class);
        assertThatThrownBy(() -> parser.parseZip(zip(file(date.plusDays(1)), csv), date, 1))
            .isInstanceOf(InstrumentMasterException.class);
        assertThatThrownBy(() -> parser.parseZip(zip("unknown.csv", csv), date, 1))
            .isInstanceOf(InstrumentMasterException.class);
    }

    @Test
    void wrongColumnCountAndDuplicateSecurityCodesRejectWholeSource() throws Exception {
        assertThatThrownBy(() -> parser.parseZip(zip(file(date), "500325,RELIANCE,EQ"), date, 1))
            .isInstanceOf(InstrumentMasterException.class);
        String duplicated = row("500325", "RELIANCE", "INE002A01018", "EQ", "A", "E")
            + "\n" + row("500325", "OTHER", "INE002A01018", "EQ", "A", "E");
        assertThatThrownBy(() -> parser.parseZip(zip(file(date), duplicated), date, 1))
            .isInstanceOf(InstrumentMasterException.class);
    }

    @Test
    void officialBseArchiveShaIsStableForImmutableCustody() throws Exception {
        byte[] z = zip(file(date), row("500325", "RELIANCE", "INE002A01018", "EQ", "A", "E"));
        assertThat(BseSecurityMasterService.checksum(z)).matches("[0-9a-f]{64}");
        assertThat(BseSecurityMasterService.checksum(z))
            .isEqualTo(BseSecurityMasterService.checksum(z));
    }

    private static String row(String code, String symbol, String isin,
                              String securityType, String tradingStatus, String instrumentType) {
        var values = new ArrayList<>(Collections.nCopies(64, ""));
        values.set(0, code);
        values.set(1, symbol);
        values.set(2, "A");
        values.set(3, "SCRIPNAME");
        values.set(4, isin);
        values.set(5, "1");
        values.set(6, "100");
        values.set(7, securityType);
        values.set(59, tradingStatus);
        values.set(63, instrumentType);
        return String.join(",", values);
    }

    private static String file(LocalDate d) {
        return "BSE_EQ_SCRIP_" + d.format(FILE_DATE) + ".csv";
    }

    private static byte[] zip(String filename, String text) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(filename));
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
