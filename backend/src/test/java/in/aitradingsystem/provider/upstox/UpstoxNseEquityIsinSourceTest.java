package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class UpstoxNseEquityIsinSourceTest {
    private static byte[] compressed(String text) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zipped = new GZIPOutputStream(output)) {
            zipped.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    @Test
    void matchesOnlyExactCashEquitySymbolAndValidIsinKey() throws Exception {
        var decoded = UpstoxNseEquityIsinSource.parseGzip(compressed("""
            [
              {"segment":"NSE_EQ","exchange":"NSE","instrument_type":"EQ",
               "security_type":"NORMAL","trading_symbol":"RELIANCE",
               "isin":"INE002A01018","instrument_key":"NSE_EQ|INE002A01018"},
              {"segment":"NSE_FO","exchange":"NSE","instrument_type":"CE",
               "trading_symbol":"RELIANCE","isin":"INE002A01018",
               "instrument_key":"NSE_EQ|INE002A01018"},
              {"segment":"NSE_EQ","exchange":"NSE","instrument_type":"BE",
               "trading_symbol":"RELIANCE","isin":"INE002A01018",
               "instrument_key":"NSE_EQ|INE002A01018"},
              {"segment":"NSE_EQ","exchange":"NSE","instrument_type":"EQ",
               "security_type":"NORMAL","trading_symbol":"BAD",
               "isin":"INE002A01018","instrument_key":"NSE_EQ|WRONG"}
            ]
            """), new ObjectMapper());
        assertThat(decoded.symbolToIsin()).containsOnlyKeys("RELIANCE");
        assertThat(decoded.symbolToIsin().get("RELIANCE")).isEqualTo("INE002A01018");
        assertThat(decoded.rawRows()).isEqualTo(4);
        assertThat(decoded.validRows()).isEqualTo(1);
    }

    @Test
    void ambiguousReusedSymbolsNeverProduceAnIsin() throws Exception {
        var result = UpstoxNseEquityIsinSource.parseGzip(compressed("""
            [
              {"segment":"NSE_EQ","exchange":"NSE","instrument_type":"EQ",
               "trading_symbol":"ABC","isin":"INE002A01018",
               "instrument_key":"NSE_EQ|INE002A01018"},
              {"segment":"NSE_EQ","exchange":"NSE","instrument_type":"EQ",
               "trading_symbol":"ABC","isin":"INE003A01019",
               "instrument_key":"NSE_EQ|INE003A01019"}
            ]
            """), new ObjectMapper());
        assertThat(result.symbolToIsin()).isEmpty();
    }

    @Test
    void corruptArchiveAndHugeInputAreRejected() {
        assertThatThrownBy(() -> UpstoxNseEquityIsinSource.parseGzip(
            "not a gzip".getBytes(StandardCharsets.UTF_8), new ObjectMapper()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UpstoxNseEquityIsinSource.parseGzip(
            new byte[33 * 1024 * 1024], new ObjectMapper()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
