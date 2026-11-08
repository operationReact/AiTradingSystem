package in.aitradingsystem.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class InstrumentMasterParserTest {
    private final InstrumentMasterParser parser = new InstrumentMasterParser(new ObjectMapper());

    @Test
    void parsesEquitySegmentAndNormalizesTickSizeFromPaise() {
        var result = parser.parse(bytes("""
                [{"token":"2885","symbol":"RELIANCE-EQ","name":"RELIANCE","expiry":"",
                  "strike":"-1.000000","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5.000000"}]
                """), 10);

        assertThat(result.parsedCount()).isEqualTo(1);
        assertThat(result.rejectedCount()).isZero();
        var instrument = result.accepted().get(0);
        assertThat(instrument.exchange()).isEqualTo("NSE");
        assertThat(instrument.exchangeSegment()).isEqualTo("NSE_CM");
        assertThat(instrument.symbolToken()).isEqualTo("2885");
        assertThat(instrument.strike()).isNull();
        assertThat(instrument.tickSize()).isEqualByComparingTo("0.05");
        assertThat(instrument.lotSize()).isEqualTo(1);
    }

    @Test
    void parsesDerivativeExpiryAndNormalizesStrike() {
        var result = parser.parse(bytes("""
                [{"token":"12345","symbol":"NIFTY29MAY25000CE","name":"NIFTY","expiry":"29MAY2025",
                  "strike":"2500000.000000","lotsize":"25","instrumenttype":"OPTIDX","exch_seg":"nse_fo","tick_size":"5.000000"}]
                """), 10);

        var instrument = result.accepted().get(0);
        assertThat(instrument.exchange()).isEqualTo("NFO");
        assertThat(instrument.expiry()).isEqualTo(LocalDate.of(2025, 5, 29));
        assertThat(instrument.strike()).isEqualByComparingTo("25000");
        assertThat(instrument.tickSize()).isEqualByComparingTo("0.05");
    }

    @Test
    void normalizesOfficialAngelOneExchangeAliasesAndParsesIsoExpiry() {
        var result = parser.parse(bytes("""
                [
                  {"token":"2885","symbol":"RELIANCE-EQ","name":"RELIANCE","expiry":"","strike":"-1.000000","lotsize":"1","instrumenttype":"EQ","exch_seg":"NSE","tick_size":"5.000000"},
                  {"token":"500325","symbol":"RELIANCE","name":"RELIANCE","expiry":"","strike":"-1.000000","lotsize":"1","instrumenttype":"EQ","exch_seg":"BSE","tick_size":"5.000000"},
                  {"token":"12345","symbol":"NIFTY26NOV2625000CE","name":"NIFTY","expiry":"2026-11-26","strike":"2500000.000000","lotsize":"25","instrumenttype":"OPTIDX","exch_seg":"NFO","tick_size":"5.000000"},
                  {"token":"12346","symbol":"SENSEX26NOV2690000CE","name":"SENSEX","expiry":"26NOV2026","strike":"9000000.000000","lotsize":"20","instrumenttype":"OPTIDX","exch_seg":"BFO","tick_size":"5.000000"},
                  {"token":"12347","symbol":"CRUDEOIL26NOV26FUT","name":"CRUDEOIL","expiry":"2026-11-26","strike":"-1.000000","lotsize":"100","instrumenttype":"FUTCOM","exch_seg":"MCX","tick_size":"1.000000"}
                ]
                """), 10);

        assertThat(result.parsedCount()).isEqualTo(5);
        assertThat(result.rejectedCount()).isZero();
        assertThat(result.accepted()).extracting(InstrumentMasterParser.InstrumentDraft::exchangeSegment)
                .containsExactly("NSE_CM", "BSE_CM", "NSE_FO", "BSE_FO", "MCX_FO");
        assertThat(result.accepted()).extracting(InstrumentMasterParser.InstrumentDraft::exchange)
                .containsExactly("NSE", "BSE", "NFO", "BFO", "MCX");
        assertThat(result.accepted().get(2).expiry()).isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(result.accepted().get(3).expiry()).isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(result.accepted().get(2).strike()).isEqualByComparingTo("25000");
    }

    @Test
    void rejectsRowsWithInvalidTokenLotTickOrSegmentWithoutAbortingWholeFile() {
        var result = parser.parse(bytes("""
                [
                  {"token":"2885","symbol":"RELIANCE-EQ","name":"RELIANCE","expiry":"","strike":"-1.000000","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5.000000"},
                  {"token":"nope","symbol":"BROKEN","name":"BROKEN","expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5"},
                  {"token":"44","symbol":"BAD","name":"BAD","expiry":"","strike":"-1","lotsize":"0","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5"},
                  {"token":"55","symbol":"BADSEG","name":"BADSEG","expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ","exch_seg":"crypto","tick_size":"5"}
                ]
                """), 10);

        assertThat(result.parsedCount()).isEqualTo(4);
        assertThat(result.accepted()).hasSize(1);
        assertThat(result.rejectedCount()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidJsonEmptyAndOversizedMaster() {
        assertThatThrownBy(() -> parser.parse(bytes("not-json"), 10))
                .isInstanceOf(InstrumentMasterException.class);
        assertThatThrownBy(() -> parser.parse(bytes("[]"), 10))
                .isInstanceOf(InstrumentMasterException.class);
        assertThatThrownBy(() -> parser.parse(bytes("[{},{}]"), 1))
                .isInstanceOf(InstrumentMasterException.class);
    }

    @Test
    void invalidExpiryAndNegativeStrikeAreRejected() {
        var result = parser.parse(bytes("""
                [
                 {"token":"1","symbol":"BAD1","name":"A","expiry":"NOTADATE","strike":"-1","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5"},
                 {"token":"2","symbol":"BAD2","name":"B","expiry":"","strike":"-20","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5"}
                ]
                """), 10);
        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejectedCount()).isEqualTo(2);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
