package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.InstrumentMasterException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/market-data/research")
public class MarketDataResearchController {
    private final MarketDataResearchExportService service;

    public MarketDataResearchController(MarketDataResearchExportService service) {
        this.service = service;
    }

    @GetMapping("/candles")
    public MarketDataResearchExport candles(
            @RequestParam @Min(1) long instrumentId,
            @RequestParam HistoricalCandleInterval interval,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "20000") @Min(1) @Max(20_000) int limit) {
        return service.export(instrumentId, interval, fromDate, toDate, limit);
    }

    @ExceptionHandler(MarketDataException.class)
    ProblemDetail marketDataError(MarketDataException ex) {
        HttpStatus status = switch (ex.code()) {
            case "INVALID_DATE_RANGE", "FUTURE_DATE_NOT_ALLOWED", "DATE_RANGE_TOO_LARGE",
                 "INVALID_INSTRUMENT_ID", "INVALID_INTERVAL", "INVALID_PAGE", "INVALID_PAGE_SIZE",
                 "INVALID_RESEARCH_LIMIT" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail instrumentError(InstrumentMasterException ex) {
        HttpStatus status = "INSTRUMENT_NOT_FOUND".equals(ex.code())
                ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }
}
