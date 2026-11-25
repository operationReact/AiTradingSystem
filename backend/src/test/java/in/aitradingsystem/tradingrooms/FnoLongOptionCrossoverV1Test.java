package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoLongOptionCrossoverV1Test {
    private static final Instant AS_OF = Instant.parse("2026-11-15T12:00:00Z");

    private static List<FnoLongOptionCrossoverV1.CompletedBar> bars(
            int flatCount, String... finalCloses) {
        int count = flatCount + finalCloses.length;
        var out = new ArrayList<FnoLongOptionCrossoverV1.CompletedBar>();
        for (int i = 0; i < count; i++) {
            Instant closeTime = AS_OF.minus(Duration.ofDays(count - i));
            BigDecimal close = i < flatCount
                    ? new BigDecimal("100.00")
                    : new BigDecimal(finalCloses[i - flatCount]);
            out.add(new FnoLongOptionCrossoverV1.CompletedBar(
                    closeTime, closeTime.plusSeconds(60), close));
        }
        return out;
    }

    @Test
    void longCallIsResearchCandidateNotBrokerOrPaperOrder() {
        var decision = FnoLongOptionCrossoverV1.evaluate(
                FnoLongOptionCrossoverV1.UNDERLYING, true,
                bars(34, "125.00"), AS_OF);
        assertThat(decision.status()).isEqualTo(
                FnoLongOptionCrossoverV1.Status.RESEARCH_CANDIDATE);
        assertThat(decision.strategyId()).isEqualTo("strategy.STR-181");
        assertThat(decision.optionType()).isEqualTo("CE");
        assertThat(decision.executable()).isFalse();
        assertThat(decision.fastEma()).isGreaterThan(decision.slowEma());
    }

    @Test
    void longPutIsResearchCandidateNotShortOption() {
        var decision = FnoLongOptionCrossoverV1.evaluate(
                FnoLongOptionCrossoverV1.UNDERLYING, true,
                bars(34, "75.00"), AS_OF);
        assertThat(decision.status()).isEqualTo(
                FnoLongOptionCrossoverV1.Status.RESEARCH_CANDIDATE);
        assertThat(decision.strategyId()).isEqualTo("strategy.STR-182");
        assertThat(decision.optionType()).isEqualTo("PE");
        assertThat(decision.executable()).isFalse();
        assertThat(decision.fastEma()).isLessThan(decision.slowEma());
    }

    @Test
    void ongoingTrendDoesNotGenerateRepeatedEntries() {
        var decision = FnoLongOptionCrossoverV1.evaluate(
                FnoLongOptionCrossoverV1.UNDERLYING, true,
                bars(34, "120.00", "125.00"), AS_OF);
        assertThat(decision.status()).isEqualTo(
                FnoLongOptionCrossoverV1.Status.NO_CROSSOVER);
        assertThat(decision.optionType()).isNull();
        assertThat(decision.executable()).isFalse();
    }

    @Test
    void absentSourceRightsFailClosedBeforeReadingCandles() {
        var decision = FnoLongOptionCrossoverV1.evaluate(
                FnoLongOptionCrossoverV1.UNDERLYING, false,
                bars(34, "120.00"), AS_OF);
        assertThat(decision.status()).isEqualTo(
                FnoLongOptionCrossoverV1.Status.BLOCKED_RIGHTS);
        assertThat(decision.reason()).isEqualTo("RESEARCH_SOURCE_RIGHTS_NOT_VERIFIED");
    }

    @Test
    void forwardKnownObservationsOrUnclosedCandlesAreRejected() {
        var candles = bars(34, "120.00");
        var last = candles.getLast();
        candles.set(candles.size() - 1, new FnoLongOptionCrossoverV1.CompletedBar(
                last.exchangeCloseAt(), AS_OF.plusSeconds(1), last.close()));
        var decision = FnoLongOptionCrossoverV1.evaluate(
                FnoLongOptionCrossoverV1.UNDERLYING, true, candles, AS_OF);
        assertThat(decision.status()).isEqualTo(
                FnoLongOptionCrossoverV1.Status.BLOCKED_DATA);
        assertThat(decision.reason()).isEqualTo("INCOMPLETE_OR_FORWARD_KNOWN_CANDLE");

        candles.set(candles.size() - 1,
                new FnoLongOptionCrossoverV1.CompletedBar(
                    AS_OF.plusSeconds(1), AS_OF.plusSeconds(2), new BigDecimal("120")));
        assertThat(FnoLongOptionCrossoverV1.evaluate(
                FnoLongOptionCrossoverV1.UNDERLYING, true, candles, AS_OF).status())
            .isEqualTo(FnoLongOptionCrossoverV1.Status.BLOCKED_DATA);
    }

    @Test
    void staleDuplicateInvalidAndTooShortSeriesAreBlocked() {
        var candles = bars(34, "120.00");
        assertThat(FnoLongOptionCrossoverV1.evaluate(
                FnoLongOptionCrossoverV1.UNDERLYING, true, candles,
                AS_OF.plus(Duration.ofDays(10))).status())
            .isEqualTo(FnoLongOptionCrossoverV1.Status.BLOCKED_DATA);
        candles.set(candles.size() - 1, candles.get(candles.size() - 2));
        assertThat(FnoLongOptionCrossoverV1.evaluate(
                FnoLongOptionCrossoverV1.UNDERLYING, true, candles, AS_OF).status())
            .isEqualTo(FnoLongOptionCrossoverV1.Status.BLOCKED_DATA);
        assertThat(FnoLongOptionCrossoverV1.evaluate(
                "BSE_INDEX|SENSEX", true, bars(34, "120.00"), AS_OF).status())
            .isEqualTo(FnoLongOptionCrossoverV1.Status.BLOCKED_DATA);
        assertThat(FnoLongOptionCrossoverV1.evaluate(
                FnoLongOptionCrossoverV1.UNDERLYING, true, bars(25), AS_OF).status())
            .isEqualTo(FnoLongOptionCrossoverV1.Status.BLOCKED_DATA);
    }
}
