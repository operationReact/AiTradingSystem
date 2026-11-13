package in.aitradingsystem.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL integration proof: activation is immutable and broker identities are exact. */
class BseSecurityMasterRepositoryIT extends AbstractPostgresIT {
    @Autowired BseSecurityMasterRepository source;

    @Test
    @Transactional
    void recentOfficialSecurityCodesPromoteOnlyMatchingUntypedBseCashDrafts() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        var published = source.publish(new BseSecurityMasterParser.Parsed(
            "BSE_EQ_SCRIP_" + today.format(java.time.format.DateTimeFormatter.ofPattern("ddMMuuuu")) + ".csv",
            today, 5000,
            List.of(new BseSecurityMasterParser.Equity("500325", "RELIANCE", "INE002A01018"))),
            "0123456789abcdef".repeat(4));
        assertThat(published.verifiedEquities()).isEqualTo(1);
        assertThat(published.sourceAsOf()).isEqualTo(today);

        var untypedBse = row("BSE_CM", "BSE", "500325", "RELIANCE", null, null);
        var untypedOther = row("BSE_CM", "BSE", "500326", "FAKE", null, null);
        var untypedNse = row("NSE_CM", "NSE", "500325", "RELIANCE-EQ", null, null);
        var bseFund = row("BSE_CM", "BSE", "500325", "RELIANCE", "MF", null);
        var bseFuture = row("BSE_CM", "BSE", "500325", "RELIANCE", null,
            LocalDate.now().plusMonths(1));
        var reusedCodeWrongCompany = row("BSE_CM", "BSE", "500325", "OTHER", null, null);
        var enhanced = source.enrichForNewCatalogue(
            List.of(untypedBse, untypedOther, untypedNse, bseFund, bseFuture, reusedCodeWrongCompany));
        assertThat(enhanced.get(0).instrumentType()).isEqualTo("EQ");
        assertThat(enhanced.get(1).instrumentType()).isNull();
        assertThat(enhanced.get(2).instrumentType()).isNull();
        assertThat(enhanced.get(3).instrumentType()).isEqualTo("MF");
        assertThat(enhanced.get(4).instrumentType()).isNull();
        assertThat(enhanced.get(5).instrumentType()).isNull();
    }

    @Test
    @Transactional
    void staleSourceCannotClassifyCurrentBrokerMasterAndOlderImportCannotDowngrade() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        String sourceName = "BSE_EQ_SCRIP_" + today.format(
            java.time.format.DateTimeFormatter.ofPattern("ddMMuuuu")) + ".csv";
        var recent = new BseSecurityMasterParser.Parsed(sourceName, today, 2000,
            List.of(new BseSecurityMasterParser.Equity("532540", "TCS", "INE467B01029")));
        source.publish(recent, "fedcba9876543210".repeat(4));
        assertThatThrownBy(() -> source.publish(new BseSecurityMasterParser.Parsed(
                "BSE_EQ_SCRIP_01102026.csv", today.minusDays(20), 2000,
                List.of(new BseSecurityMasterParser.Equity("500325", "RELIANCE", "INE002A01018"))),
                "0123012301230123".repeat(4)))
            .isInstanceOf(InstrumentMasterException.class);
        assertThat(source.current().sourceAsOf()).isEqualTo(today);
    }

    @Test
    @Transactional
    void truncatedOrIncompleteOfficialReplacementCannotReplaceActiveSource() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        String file = "BSE_EQ_SCRIP_" + today.format(
            java.time.format.DateTimeFormatter.ofPattern("ddMMuuuu")) + ".csv";
        var initial = new BseSecurityMasterParser.Parsed(file, today, 5000,
            List.of(new BseSecurityMasterParser.Equity("500325", "RELIANCE", "INE002A01018"),
                new BseSecurityMasterParser.Equity("532540", "TCS", "INE467B01029")));
        source.publish(initial, "aabbccddeeff0123".repeat(4));
        var replacement = new BseSecurityMasterParser.Parsed(file, today, 2000,
            List.of(new BseSecurityMasterParser.Equity("500325", "RELIANCE", "INE002A01018")));
        assertThatThrownBy(() -> source.publish(replacement, "1000000010000000".repeat(4)))
            .isInstanceOf(InstrumentMasterException.class)
            .hasMessageContaining("shrank unexpectedly");
        assertThat(source.current().verifiedEquities()).isEqualTo(2);
        assertThat(source.current().sourceRecords()).isEqualTo(5000);
    }

    private static InstrumentMasterParser.InstrumentDraft row(
            String segment, String exchange, String token, String symbol, String type, LocalDate expiry) {
        return new InstrumentMasterParser.InstrumentDraft(segment, exchange, token, symbol,
            symbol, type, expiry, null, 1, new BigDecimal("0.05"));
    }
}
