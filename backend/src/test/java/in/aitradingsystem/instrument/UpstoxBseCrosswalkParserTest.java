package in.aitradingsystem.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class UpstoxBseCrosswalkParserTest {
    private final UpstoxBseCrosswalkParser parser =
        new UpstoxBseCrosswalkParser(new ObjectMapper());

    @Test
    void classifiesOnlyExplicitNormalBseEquitiesFromOfficialUpstoxJsonFormat() throws Exception {
        byte[] archive=gzip("""
          [
            {"segment":"BSE_EQ","exchange":"BSE","instrument_type":"A",
              "security_type":"NORMAL","isin":"INE002A01018",
              "instrument_key":"BSE_EQ|INE002A01018","exchange_token":"500325",
              "trading_symbol":"RELIANCE"},
            {"segment":"BSE_EQ","exchange":"BSE","instrument_type":"EQ",
              "security_type":"NORMAL","isin":"INE467B01029",
              "instrument_key":"BSE_EQ|INE467B01029","exchange_token":"532540",
              "trading_symbol":"TCS"},
            {"segment":"BSE_EQ","exchange":"BSE","instrument_type":"DB",
              "security_type":"NORMAL","isin":"INE002A01018",
              "instrument_key":"BSE_EQ|INE002A01018","exchange_token":"500340",
              "trading_symbol":"DEBT"},
            {"segment":"BSE_EQ","exchange":"BSE","instrument_type":"A",
              "security_type":"SUSPENDED","isin":"INE002A01018",
              "instrument_key":"BSE_EQ|INE002A01018","exchange_token":"500341",
              "trading_symbol":"SUSPEND"},
            {"segment":"BSE_INDEX","exchange":"BSE","instrument_type":"INDEX",
              "exchange_token":"1","trading_symbol":"SENSEX"}
          ]
          """);
        var parsed=parser.parseGzip(archive);
        assertThat(parsed.totalRows()).isEqualTo(5);
        assertThat(parsed.eligibleRows()).isEqualTo(2);
        assertThat(parsed.excludedRows()).isEqualTo(3);
        assertThat(parsed.verifiedEquities()).containsOnlyKeys("500325","532540");
        assertThat(parsed.verifiedEquities().get("500325").symbol()).isEqualTo("RELIANCE");
    }

    @Test
    void tokenCollisionOrIdentityMismatchRejectsWholeSource() throws Exception {
        String valid="""
            {"segment":"BSE_EQ","exchange":"BSE","instrument_type":"A",
             "security_type":"NORMAL","isin":"INE002A01018",
             "instrument_key":"BSE_EQ|INE002A01018","exchange_token":"500325",
             "trading_symbol":"RELIANCE"}
            """;
        assertThatThrownBy(() -> parser.parseGzip(gzip("["+valid+","+valid+"]")))
            .isInstanceOf(InstrumentMasterException.class);
        assertThatThrownBy(() -> parser.parseGzip(gzip("["+valid.replace(
            "BSE_EQ|INE002A01018","BSE_EQ|WRONG")+"]")))
            .isInstanceOf(InstrumentMasterException.class);
    }

    @Test
    void malformedGzipOrUnboundedJsonCannotEnterCatalogue() throws Exception {
        assertThatThrownBy(() -> parser.parseGzip("json".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(InstrumentMasterException.class);
        assertThatThrownBy(() -> parser.parseGzip(gzip("{}")))
            .isInstanceOf(InstrumentMasterException.class);
        assertThatThrownBy(() -> parser.parseGzip(gzip("[]")))
            .isInstanceOf(InstrumentMasterException.class);
    }

    private static byte[] gzip(String json) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var output=new GZIPOutputStream(bytes)){
            output.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
