package in.aitradingsystem.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AngelOneException;
import in.aitradingsystem.provider.angelone.AngelOneHistoricalService;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HistoricalCandleService {
    static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final DateTimeFormatter PROVIDER_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);
    private static final Set<String> SUPPORTED_EXCHANGES = Set.of("NSE", "NFO", "BSE", "BFO", "MCX");
    private static final String SOURCE = "ANGEL_ONE_SMARTAPI";

    private final InstrumentMasterService instruments;
    private final AngelOneHistoricalService provider;
    private final HistoricalCandleParser parser;
    private final MarketDataRepository repository;
    private final int maxRangeDays;
    private final int maxChunksPerRun;

    public HistoricalCandleService(InstrumentMasterService instruments, AngelOneHistoricalService provider,
                                   HistoricalCandleParser parser, MarketDataRepository repository,
                                   @Value("${app.market-data.max-range-days:366}") int maxRangeDays,
                                   @Value("${app.market-data.max-chunks-per-run:5}") int maxChunksPerRun) {
        this.instruments = instruments;
        this.provider = provider;
        this.parser = parser;
        this.repository = repository;
        if (maxRangeDays < 1 || maxRangeDays > 3_660) {
            throw new IllegalArgumentException("Market data maximum range must be between 1 and 3660 days");
        }
        if (maxChunksPerRun < 1 || maxChunksPerRun > 10) {
            throw new IllegalArgumentException("Market data chunk limit must be between 1 and 10");
        }
        this.maxRangeDays = maxRangeDays;
        this.maxChunksPerRun = maxChunksPerRun;
    }

    @PostConstruct
    void recoverInterruptedRuns() {
        repository.recoverInterruptedRuns();
    }

    public CandleBackfillRun start(long instrumentId, HistoricalCandleInterval interval,
                                   LocalDate fromDate, LocalDate toDate) {
        validateRange(fromDate, toDate);
        if (interval == null) throw new MarketDataException("INVALID_INTERVAL", "A supported candle interval is required");
        Instrument instrument = instruments.requireActiveInstrument(instrumentId);
        if (!SUPPORTED_EXCHANGES.contains(instrument.exchange())) {
            throw new MarketDataException("EXCHANGE_NOT_SUPPORTED",
                    "Historical candles are currently supported only for NSE, NFO, BSE, BFO and MCX");
        }
        CandleBackfillRun run = repository.createRun(UUID.randomUUID(), instrument.id(), instrument.exchange(),
                instrument.tradingSymbol(), instrument.symbolToken(), instrument.catalogueVersion(),
                interval.name(), fromDate, toDate);
        process(run.id());
        return repository.requireRun(run.id());
    }

    public CandleBackfillRun resume(UUID id) {
        repository.claimForResume(id);
        process(id);
        return repository.requireRun(id);
    }

    private void process(UUID runId) {
        CandleBackfillRun run = repository.requireRun(runId);
        int chunks = 0;
        while (!run.nextFromDate().isAfter(run.toDate()) && chunks < maxChunksPerRun) {
            LocalDate chunkFrom = run.nextFromDate();
            LocalDate chunkTo = chunkFrom.plusDays(runInterval(run).maxDaysPerRequest() - 1L);
            if (chunkTo.isAfter(run.toDate())) chunkTo = run.toDate();
            try {
                String from = LocalDateTime.of(chunkFrom, MARKET_OPEN).format(PROVIDER_DATE);
                String to = LocalDateTime.of(chunkTo, MARKET_CLOSE).format(PROVIDER_DATE);
                JsonNode response = provider.fetch(run.exchange(), run.symbolToken(), run.interval(), from, to);
                List<HistoricalCandleParser.CandleValues> values = parser.parse(response);
                validateTimestamps(values, chunkFrom, chunkTo);
                Instant fetchedAt = Instant.now();
                List<MarketCandle> bars = values.stream().map(value -> new MarketCandle(
                        0, run.instrumentId(), run.exchange(), run.tradingSymbol(), run.symbolToken(),
                        run.catalogueVersion(), run.interval(), value.candleTime(), value.open(), value.high(),
                        value.low(), value.close(), value.volume(), SOURCE, fetchedAt)).toList();
                repository.persistChunk(run.id(), chunkFrom, chunkTo, chunkTo.plusDays(1), bars);
            } catch (RuntimeException ex) {
                repository.recordChunkFailure(run.id(), chunkFrom, chunkTo, errorCode(ex), errorMessage(ex));
                return;
            }
            chunks++;
            run = repository.requireRun(runId);
        }
        repository.finishRun(runId, run.nextFromDate().isAfter(run.toDate()));
    }

    private HistoricalCandleInterval runInterval(CandleBackfillRun run) {
        try {
            return HistoricalCandleInterval.valueOf(run.interval());
        } catch (IllegalArgumentException ex) {
            throw new MarketDataException("INVALID_INTERVAL", "Backfill contains an unsupported interval");
        }
    }

    private void validateTimestamps(List<HistoricalCandleParser.CandleValues> candles,
                                    LocalDate from, LocalDate to) {
        for (HistoricalCandleParser.CandleValues candle : candles) {
            LocalDate date = candle.candleTime().atZone(MARKET_ZONE).toLocalDate();
            if (date.isBefore(from) || date.isAfter(to)) {
                throw new MarketDataException("CANDLE_OUTSIDE_REQUEST",
                        "Provider returned a candle outside the requested date window");
            }
        }
    }

    public CandlePage query(long instrumentId, HistoricalCandleInterval interval, LocalDate fromDate,
                            LocalDate toDate, int page, int size) {
        validateRange(fromDate, toDate);
        if (instrumentId < 1) throw new MarketDataException("INVALID_INSTRUMENT_ID", "Instrument ID must be positive");
        if (interval == null) throw new MarketDataException("INVALID_INTERVAL", "A supported candle interval is required");
        if (page < 0 || page > 1_000_000) throw new MarketDataException("INVALID_PAGE", "Page must be between 0 and 1000000");
        if (size < 1 || size > 500) throw new MarketDataException("INVALID_PAGE_SIZE", "Page size must be between 1 and 500");
        Instant from = fromDate.atStartOfDay(MARKET_ZONE).toInstant();
        Instant toExclusive = toDate.plusDays(1).atStartOfDay(MARKET_ZONE).toInstant();
        return repository.candles(instrumentId, interval.name(), from, toExclusive, page, size);
    }

    public CandleBackfillRun getRun(UUID id) {
        return repository.requireRun(id);
    }

    public List<CandleBackfillRun> recentRuns(int limit) {
        if (limit < 1 || limit > 50) throw new MarketDataException("INVALID_LIMIT", "Limit must be between 1 and 50");
        return repository.recentRuns(limit);
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new MarketDataException("INVALID_DATE_RANGE", "Both fromDate and toDate are required");
        if (from.isAfter(to)) throw new MarketDataException("INVALID_DATE_RANGE", "fromDate must be on or before toDate");
        if (to.isAfter(LocalDate.now(MARKET_ZONE))) {
            throw new MarketDataException("FUTURE_DATE_NOT_ALLOWED", "Historical market data cannot be requested for a future date");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > maxRangeDays) {
            throw new MarketDataException("DATE_RANGE_TOO_LARGE",
                    "Date range is limited to " + maxRangeDays + " calendar days per backfill request");
        }
    }

    private String errorCode(RuntimeException ex) {
        if (ex instanceof MarketDataException marketData) return marketData.code();
        if (ex instanceof AngelOneException angel) return angel.code();
        return "BACKFILL_FAILED";
    }

    private String errorMessage(RuntimeException ex) {
        if (ex instanceof MarketDataException || ex instanceof AngelOneException) return bounded(ex.getMessage(), 500);
        return "Backfill failed unexpectedly; the previous checkpoint remains available for resume";
    }

    private static String bounded(String value, int max) {
        if (value == null) return "Backfill failed";
        String clean = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
