package in.aitradingsystem.api;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AngelOneException;
import in.aitradingsystem.provider.angelone.AngelOneHealthService;
import in.aitradingsystem.provider.angelone.AngelOneHealthService.ProviderHealth;
import in.aitradingsystem.provider.angelone.AngelOneQuoteService;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
@Validated
@RequestMapping("/api/providers/angel-one")
public class AngelOneController {

    private final AngelOneHealthService health;
    private final AngelOneQuoteService quotes;
    private final InstrumentMasterService instruments;

    public AngelOneController(AngelOneHealthService health, AngelOneQuoteService quotes,
                              InstrumentMasterService instruments) {
        this.health = health;
        this.quotes = quotes;
        this.instruments = instruments;
    }

    public record LtpResponse(long instrumentId, String exchange, String tradingSymbol, String symbolToken,
                              BigDecimal ltp, Instant fetchedAt, String source) {}

    @GetMapping("/status")
    public ProviderHealth status() {
        return health.status();
    }

    @PostMapping("/test-connection")
    public ProviderHealth testConnection() {
        return health.testConnection();
    }

    /** Resolves all provider arguments from the active instrument catalogue; caller-supplied tokens are not trusted. */
    @GetMapping("/ltp")
    public LtpResponse ltp(@RequestParam(required = false) @Min(1) Long instrumentId) {
        if (instrumentId == null) {
            throw new InstrumentMasterException("INSTRUMENT_ID_REQUIRED",
                    "instrumentId from the active instrument catalogue is required");
        }
        Instrument instrument = instruments.requireActiveInstrument(instrumentId);
        BigDecimal ltp = quotes.ltp(instrument.exchange(), instrument.tradingSymbol(), instrument.symbolToken());
        return new LtpResponse(instrument.id(), instrument.exchange(), instrument.tradingSymbol(),
                instrument.symbolToken(), ltp, Instant.now(), "ANGEL_ONE_SMARTAPI");
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail instrumentFailure(InstrumentMasterException ex) {
        HttpStatus status = switch (ex.code()) {
            case "INSTRUMENT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INSTRUMENT_ID_REQUIRED", "INVALID_INSTRUMENT_ID" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }

    @ExceptionHandler(AngelOneException.class)
    ProblemDetail providerFailure(AngelOneException ex) {
        HttpStatus status = switch (ex.kind()) {
            case NOT_CONFIGURED, UNAVAILABLE, SESSION_EXPIRED -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case REJECTED, INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        pd.setProperty("providerCode", ex.code());
        return pd;
    }
}
