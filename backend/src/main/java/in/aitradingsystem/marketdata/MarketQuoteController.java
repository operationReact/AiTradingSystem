package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AngelOneException;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Home-screen quote API. The response contract does not expose broker tokens or broker credentials. */
@RestController
@Validated
@RequestMapping("/api/market-data")
public class MarketQuoteController {
    private final InstrumentMasterService instruments;
    private final MarketDataProvider provider;

    public MarketQuoteController(InstrumentMasterService instruments, MarketDataProvider provider) {
        this.instruments = instruments;
        this.provider = provider;
    }

    public record Quote(long instrumentId, String exchange, String tradingSymbol,
                        BigDecimal ltp, Instant fetchedAt, String source) {}

    @GetMapping("/quotes")
    public Quote quote(@RequestParam @Min(1) long instrumentId) {
        Instrument instrument = instruments.requireActiveInstrument(instrumentId);
        BigDecimal ltp = provider.lastTradedPrice(instrument);
        return new Quote(instrument.id(), instrument.exchange(), instrument.tradingSymbol(),
                ltp, Instant.now(), provider.source());
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail instrumentError(InstrumentMasterException exception) {
        HttpStatus status = "INSTRUMENT_NOT_FOUND".equals(exception.code())
                ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        detail.setProperty("code", exception.code());
        return detail;
    }

    @ExceptionHandler(AngelOneException.class)
    ProblemDetail providerError(AngelOneException exception) {
        HttpStatus status = switch (exception.kind()) {
            case NOT_CONFIGURED, UNAVAILABLE, SESSION_EXPIRED -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case REJECTED, INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        detail.setProperty("providerCode", exception.code());
        return detail;
    }
}
