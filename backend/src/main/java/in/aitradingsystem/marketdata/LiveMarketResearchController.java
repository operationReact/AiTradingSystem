package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.provider.angelone.AngelOneException;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only observed market evidence. This endpoint does not place, modify, cancel, or authorize orders. */
@RestController
@Validated
@RequestMapping("/api/market-data/research")
public class LiveMarketResearchController {
    private final LiveMarketResearchQuoteService service;

    public LiveMarketResearchController(LiveMarketResearchQuoteService service) {
        this.service = service;
    }

    @GetMapping("/live-quote")
    public LiveMarketResearchQuote liveQuote(@RequestParam @Min(1) long instrumentId) {
        return service.quote(instrumentId);
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail instrumentError(InstrumentMasterException ex) {
        HttpStatus status = "INSTRUMENT_NOT_FOUND".equals(ex.code())
                ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
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
