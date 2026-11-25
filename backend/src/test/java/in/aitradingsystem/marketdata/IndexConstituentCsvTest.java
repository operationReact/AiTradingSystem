package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IndexConstituentCsvTest {
    private static final IndexConstituentCsv.Universe THREE = new IndexConstituentCsv.Universe("TEST", 3, 0);

    private static byte[] csv(String body) {
        return ("﻿Company Name,Industry,Symbol,Series,ISIN Code\r\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesOfficialListIncludingQuotedCommasAndBom() {
        var members = IndexConstituentCsv.parse(csv("""
                Aadhar Housing Finance Ltd.,Financial Services,AADHARHFC,EQ,INE883F01010
                "Mahindra & Mahindra, Ltd.",Automobile and Auto Components,M&M,EQ,INE101A01026
                Bajaj Auto Ltd.,Automobile and Auto Components,BAJAJ-AUTO,EQ,INE917I01010
                """), THREE);
        assertThat(members).extracting(IndexConstituentCsv.Member::tradingSymbol)
            .containsExactly("AADHARHFC-EQ", "M&M-EQ", "BAJAJ-AUTO-EQ");
        assertThat(members.get(1).companyName()).isEqualTo("Mahindra & Mahindra, Ltd.");
        assertThat(members.get(0).industry()).isEqualTo("Financial Services");
    }

    @Test
    void rejectsWrongMemberCountRatherThanApplyingPartialList() {
        assertThatThrownBy(() -> IndexConstituentCsv.parse(csv("""
                Alpha Ltd.,Financial Services,ALPHA,EQ,INE000A01010
                """), THREE)).isInstanceOf(MarketDataException.class).hasMessageContaining("expected 3");
    }

    @Test
    void rejectsNonEquitySeriesInvalidSymbolsAndDuplicates() {
        assertThatThrownBy(() -> IndexConstituentCsv.parse(csv("""
                A Ltd.,X,AAA,BE,INE000A01010
                B Ltd.,X,BBB,EQ,INE000A01011
                C Ltd.,X,CCC,EQ,INE000A01012
                """), THREE)).hasMessageContaining("series");
        assertThatThrownBy(() -> IndexConstituentCsv.parse(csv("""
                A Ltd.,X,AA A,EQ,INE000A01010
                B Ltd.,X,BBB,EQ,INE000A01011
                C Ltd.,X,CCC,EQ,INE000A01012
                """), THREE)).hasMessageContaining("symbol");
        assertThatThrownBy(() -> IndexConstituentCsv.parse(csv("""
                A Ltd.,X,AAA,EQ,INE000A01010
                B Ltd.,X,AAA,EQ,INE000A01011
                C Ltd.,X,CCC,EQ,INE000A01012
                """), THREE)).hasMessageContaining("Duplicate");
    }

    @Test
    void rejectsMissingColumns() {
        byte[] payload = "Company Name,Symbol,Series\nA,AAA,EQ\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> IndexConstituentCsv.parse(payload, THREE)).hasMessageContaining("industry");
    }

    @Test
    void mapsOnlyConstituentFamiliesToUniverses() {
        assertThat(IndexConstituentCsv.universeFor(OfficialMarketSourceFamily.NIFTY_MICROCAP250_CONSTITUENTS))
            .get().extracting(IndexConstituentCsv.Universe::code).isEqualTo("MICROCAP250");
        assertThat(IndexConstituentCsv.universeFor(OfficialMarketSourceFamily.NIFTY100_CONSTITUENTS))
            .get().extracting(IndexConstituentCsv.Universe::expectedMembers).isEqualTo(100);
        assertThat(IndexConstituentCsv.universeFor(OfficialMarketSourceFamily.INDIA_VIX_HISTORY)).isEmpty();
    }
}
