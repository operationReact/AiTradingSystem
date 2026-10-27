package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.provider.angelone.AngelOneException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/market-data")
public class MarketDataController {
    private final HistoricalCandleService service;

    public MarketDataController(HistoricalCandleService service) {
        this.service = service;
    }

    public record BackfillRequest(
            @Min(1) long instrumentId,
            @NotNull HistoricalCandleInterval interval,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {}

    @GetMapping("/candles")
    public CandlePage candles(
            @RequestParam @Min(1) long instrumentId,
            @RequestParam HistoricalCandleInterval interval,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
        return service.query(instrumentId, interval, fromDate, toDate, page, size);
    }

    @PostMapping("/backfills")
    public CandleBackfillRun start(@Valid @RequestBody BackfillRequest request) {
        return service.start(request.instrumentId(), request.interval(), request.fromDate(), request.toDate());
    }

    @GetMapping("/backfills")
    public List<CandleBackfillRun> recent(
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return service.recentRuns(limit);
    }

    @GetMapping("/backfills/{id}")
    public CandleBackfillRun get(@PathVariable UUID id) {
        return service.getRun(id);
    }

    @PostMapping("/backfills/{id}/resume")
    public CandleBackfillRun resume(@PathVariable UUID id) {
        return service.resume(id);
    }

    @ExceptionHandler(MarketDataException.class)
    ProblemDetail marketDataError(MarketDataException ex) {
        HttpStatus status = switch (ex.code()) {
            case "BACKFILL_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "BACKFILL_ALREADY_RUNNING", "BACKFILL_NOT_RESUMABLE" -> HttpStatus.CONFLICT;
            case "INVALID_DATE_RANGE", "FUTURE_DATE_NOT_ALLOWED", "DATE_RANGE_TOO_LARGE",
                 "INVALID_INSTRUMENT_ID", "INVALID_INTERVAL", "INVALID_PAGE", "INVALID_PAGE_SIZE",
                 "INVALID_LIMIT" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail instrumentError(InstrumentMasterException ex) {
        HttpStatus status = "INSTRUMENT_NOT_FOUND".equals(ex.code()) ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }

    @ExceptionHandler(AngelOneException.class)
    ProblemDetail providerError(AngelOneException ex) {
        HttpStatus status = switch (ex.kind()) {
            case NOT_CONFIGURED, UNAVAILABLE, SESSION_EXPIRED -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case REJECTED, INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("providerCode", ex.code());
        return problem;
    }
}
