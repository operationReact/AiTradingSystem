package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class HistoricalCandleParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HistoricalCandleParser parser = new HistoricalCandleParser();

    @Test
    void parsesOfficialCandleTupleAndOffsetTimestamp() throws Exception {
        JsonNode input = mapper.readTree("""
                [["2026-10-26T09:15:00+05:30",100,105,99,104,2500]]
                """);
        var result = parser.parse(input);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).candleTime().toString()).isEqualTo("2026-10-26T03:45:00Z");
        assertThat(result.get(0).close()).isEqualByComparingTo("104");
        assertThat(result.get(0).volume()).isEqualTo(2500);
    }

    @Test
    void rejectsRowsWithIncorrectShape() throws Exception {
        JsonNode input = mapper.readTree("""
                [["2026-10-26T09:15:00+05:30",100,105,99,104]]
                """);
        assertThatThrownBy(() -> parser.parse(input)).isInstanceOf(MarketDataException.class)
                .hasMessageContaining("exactly six");
    }

    @Test
    void rejectsInconsistentOhlcAndNegativeVolume() throws Exception {
        JsonNode badOhlc = mapper.readTree("""
                [["2026-10-26T09:15:00+05:30",100,98,99,104,10]]
                """);
        assertThatThrownBy(() -> parser.parse(badOhlc)).isInstanceOf(MarketDataException.class)
                .hasMessageContaining("OHLC");
        JsonNode badVolume = mapper.readTree("""
                [["2026-10-26T09:15:00+05:30",100,105,99,104,-1]]
                """);
        assertThatThrownBy(() -> parser.parse(badVolume)).isInstanceOf(MarketDataException.class)
                .hasMessageContaining("volume");
    }

    @Test
    void deduplicatesIdenticalTimestampsAndRejectsConflicts() throws Exception {
        JsonNode duplicate = mapper.readTree("""
                [["2026-10-26T09:15:00+05:30",100,105,99,104,10],
                 ["2026-10-26T09:15:00+05:30",100.0,105.00,99,104,10]]
                """);
        assertThat(parser.parse(duplicate)).hasSize(1);
        JsonNode conflict = mapper.readTree("""
                [["2026-10-26T09:15:00+05:30",100,105,99,104,10],
                 ["2026-10-26T09:15:00+05:30",100,106,99,104,10]]
                """);
        assertThatThrownBy(() -> parser.parse(conflict)).isInstanceOf(MarketDataException.class)
                .hasMessageContaining("conflicting candles");
    }

    @Test
    void rejectsTimestampsWithoutExplicitOffset() throws Exception {
        JsonNode input = mapper.readTree("""
                [["2026-10-26T09:15:00",100,105,99,104,10]]
                """);
        assertThatThrownBy(() -> parser.parse(input)).isInstanceOf(MarketDataException.class)
                .hasMessageContaining("UTC offset");
    }
}
