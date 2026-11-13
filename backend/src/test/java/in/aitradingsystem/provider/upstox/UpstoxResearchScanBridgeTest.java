package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.scanner.ScannerEngine;
import in.aitradingsystem.scanner.VolumeBreakoutStrategy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UpstoxResearchScanBridgeTest {
    private static final Instant START = Instant.parse("2026-11-12T04:00:00Z");

    private static Instrument instrument() {
        return new Instrument(42L, "NSE_EQ", "NSE", "2885", "RELIANCE-EQ",
                "Reliance Industries", "EQ", null, null, 1,
                new BigDecimal("0.05"), UUID.randomUUID());
    }

    private static UpstoxResearchScanBridge adapter() {
        return new UpstoxResearchScanBridge(
                new ScannerEngine(List.of(new VolumeBreakoutStrategy()),
                        Duration.ofMinutes(60)));
    }

    private static UpstoxAnalyticsSource.Candle bar(Instant time, String close,
                                                      String high, String low, long volume) {
        return new UpstoxAnalyticsSource.Candle(time, new BigDecimal("101"),
                new BigDecimal(high), new BigDecimal(low), new BigDecimal(close),
                volume, 0L);
    }

    private static List<UpstoxAnalyticsSource.Candle> sample() {
        List<UpstoxAnalyticsSource.Candle> bars = new ArrayList<>();
        for (int i = 0; i < 63; i++) {
            bars.add(bar(START.plusSeconds(60L * i), "101", "103", "98", 100));
        }
        bars.add(bar(START.plusSeconds(60L * 63), "120", "123", "100", 1000));
        return bars;
    }

    @Test
    void convertsUpstoxCandlesIntoExistingBreakoutResearchWithoutTrading() {
        var preview = adapter().preview(instrument(), "NSE_EQ|INE002A01018",
                HistoricalCandleInterval.ONE_MINUTE,
                START.plusSeconds(64L * 60), sample());
        assertThat(preview.source()).isEqualTo("UPSTOX_ANALYTICS_READONLY");
        assertThat(preview.validatedCandles()).isEqualTo(64);
        assertThat(preview.candidates()).hasSize(1);
        assertThat(preview.candidates().getFirst().strategy())
                .isEqualTo(VolumeBreakoutStrategy.NAME);
        assertThat(preview.candidates().getFirst().referencePrice())
                .isEqualByComparingTo("120");
        assertThat(preview.instrumentMappingVerified()).isFalse();
        assertThat(preview.executionEnabled()).isFalse();
    }

    @Test
    void staleDataDoesNotProduceCandidate() {
        var preview = adapter().preview(instrument(), "NSE_EQ|INE002A01018",
                HistoricalCandleInterval.ONE_MINUTE,
                START.plusSeconds(63L * 60 + 7200), sample());
        assertThat(preview.candidates()).isEmpty();
        assertThat(preview.executionEnabled()).isFalse();
    }

    @Test
    void rejectsBadKeyDailyInputAndFutureOrDuplicateBars() {
        var bridge = adapter();
        var now = START.plusSeconds(64L * 60);
        assertThatThrownBy(() -> bridge.preview(instrument(), "BSE_EQ|INE002A01018",
                HistoricalCandleInterval.ONE_MINUTE, now, sample()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bridge.preview(instrument(), "NSE_EQ|INE002A01018",
                HistoricalCandleInterval.ONE_DAY, now, sample()))
                .isInstanceOf(IllegalArgumentException.class);
        var duplicate = new ArrayList<>(sample());
        duplicate.set(30, duplicate.get(29));
        assertThatThrownBy(() -> bridge.preview(instrument(), "NSE_EQ|INE002A01018",
                HistoricalCandleInterval.ONE_MINUTE, now, duplicate))
                .hasMessageContaining("duplicate");
        var future = new ArrayList<>(sample());
        future.set(63, bar(now.plusSeconds(60), "120", "123", "100", 1000));
        assertThatThrownBy(() -> bridge.preview(instrument(), "NSE_EQ|INE002A01018",
                HistoricalCandleInterval.ONE_MINUTE, now, future))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
