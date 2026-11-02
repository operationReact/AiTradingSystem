package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class MarketDataResearchExportService {
    static final String CONTRACT_VERSION = "market-history.v1";
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final int PAGE_SIZE = 500;
    private static final int MAX_ROWS = 20_000;

    private final HistoricalCandleService candles;
    private final InstrumentMasterService instruments;

    public MarketDataResearchExportService(HistoricalCandleService candles, InstrumentMasterService instruments) {
        this.candles = candles;
        this.instruments = instruments;
    }

    public MarketDataResearchExport export(long instrumentId, HistoricalCandleInterval interval,
                                           LocalDate fromDate, LocalDate toDate, int limit) {
        if (limit < 1 || limit > MAX_ROWS) {
            throw new MarketDataException("INVALID_RESEARCH_LIMIT",
                    "Research export limit must be between 1 and " + MAX_ROWS);
        }
        Instrument instrument = instruments.requireActiveInstrument(instrumentId);
        List<MarketCandle> rows = new ArrayList<>(Math.min(limit, PAGE_SIZE));
        int page = 0;
        while (rows.size() < limit) {
            int remaining = limit - rows.size();
            CandlePage result = candles.query(instrumentId, interval, fromDate, toDate, page,
                    Math.min(PAGE_SIZE, remaining));
            rows.addAll(result.items());
            if (!result.hasNext() || result.items().isEmpty()) break;
            page++;
        }

        List<MarketDataResearchExport.ResearchCandle> researchRows = rows.stream()
                .sorted(Comparator.comparing(MarketCandle::candleTime))
                .map(row -> new MarketDataResearchExport.ResearchCandle(
                        row.candleTime(), availableAt(row.candleTime(), interval), row.open(), row.high(),
                        row.low(), row.close(), row.volume(), row.source(), row.fetchedAt()))
                .toList();
        Instant dataCutoff = researchRows.stream()
                .map(MarketDataResearchExport.ResearchCandle::availableAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        List<String> sources = rows.stream()
                .map(MarketCandle::source)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        return new MarketDataResearchExport(
                CONTRACT_VERSION,
                Instant.now(),
                instrument.id(),
                instrument.exchange(),
                instrument.tradingSymbol(),
                instrument.symbolToken(),
                instrument.catalogueVersion(),
                interval.name(),
                fromDate.atStartOfDay(MARKET_ZONE).toInstant(),
                toDate.plusDays(1).atStartOfDay(MARKET_ZONE).toInstant(),
                dataCutoff,
                sources,
                researchRows);
    }

    static Instant availableAt(Instant candleTime, HistoricalCandleInterval interval) {
        if (interval == HistoricalCandleInterval.ONE_DAY) {
            return candleTime.atZone(MARKET_ZONE).toLocalDate()
                    .atTime(MARKET_CLOSE).atZone(MARKET_ZONE).toInstant();
        }
        return candleTime.plus(intervalDuration(interval));
    }

    private static Duration intervalDuration(HistoricalCandleInterval interval) {
        return switch (interval) {
            case ONE_MINUTE -> Duration.ofMinutes(1);
            case THREE_MINUTE -> Duration.ofMinutes(3);
            case FIVE_MINUTE -> Duration.ofMinutes(5);
            case TEN_MINUTE -> Duration.ofMinutes(10);
            case FIFTEEN_MINUTE -> Duration.ofMinutes(15);
            case THIRTY_MINUTE -> Duration.ofMinutes(30);
            case ONE_HOUR -> Duration.ofHours(1);
            case ONE_DAY -> throw new IllegalArgumentException("Daily bars use the market-close availability rule");
        };
    }
}
