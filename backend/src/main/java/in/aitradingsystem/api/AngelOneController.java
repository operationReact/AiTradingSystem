package in.aitradingsystem.api;

import in.aitradingsystem.provider.angelone.AngelOneException;
import in.aitradingsystem.provider.angelone.AngelOneHealthService;
import in.aitradingsystem.provider.angelone.AngelOneHealthService.ProviderHealth;
import in.aitradingsystem.provider.angelone.AngelOneQuoteService;
import jakarta.validation.constraints.Pattern;
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

    public AngelOneController(AngelOneHealthService health, AngelOneQuoteService quotes) {
        this.health = health;
        this.quotes = quotes;
    }

    public record LtpResponse(String exchange, String tradingSymbol, String symbolToken, BigDecimal ltp,
                              Instant fetchedAt, String source) {}

    @GetMapping("/status")
    public ProviderHealth status() {
        return health.status();
    }

    @PostMapping("/test-connection")
    public ProviderHealth testConnection() {
        return health.testConnection();
    }

    @GetMapping("/ltp")
    public LtpResponse ltp(@RequestParam @Pattern(regexp = "NSE|BSE") String exchange,
                           @RequestParam @Pattern(regexp = "[A-Za-z0-9&._-]{1,40}") String tradingSymbol,
                           @RequestParam @Pattern(regexp = "[0-9]{1,12}") String symbolToken) {
        BigDecimal ltp = quotes.ltp(exchange, tradingSymbol, symbolToken);
        return new LtpResponse(exchange, tradingSymbol, symbolToken, ltp, Instant.now(), "ANGEL_ONE_SMARTAPI");
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
