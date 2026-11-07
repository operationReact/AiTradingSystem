package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import in.aitradingsystem.instrument.InstrumentMasterException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A durable, non-executing customer trade intent audit in both modes.
 * There is deliberately no submit/place/execute operation in this controller.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/orders/intents")
public class CustomerTradeIntentController {
    private final CustomerTradeIntentService service;
    private final CustomerIdentityStore identities;

    public CustomerTradeIntentController(CustomerTradeIntentService service,
                                         CustomerIdentityStore identities) {
        this.service = service;
        this.identities = identities;
    }

    public record CreateIntentRequest(
        @NotNull @Min(1) Long instrumentId,
        @NotNull @Pattern(regexp="BUY|SELL") String side,
        @NotNull @Min(1) @Max(10000) Integer quantity,
        @NotNull @Pattern(regexp="DELIVERY|INTRADAY") String productType,
        @NotNull @DecimalMin("0.01") @DecimalMax("10000000")
            @Digits(integer=8, fraction=2) BigDecimal limitPrice) {}

    @PostMapping
    public ResponseEntity<CustomerTradeIntentService.Intent> create(
        @PathVariable UUID roomId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody CreateIntentRequest input,
        Authentication authentication) {
        var result = service.create(
            customer(authentication), roomId, idempotencyKey,
            input.instrumentId(), input.side(), input.quantity(),
            input.productType(), input.limitPrice());
        return ResponseEntity.status(HttpStatus.OK)
            .cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping
    public ResponseEntity<List<CustomerTradeIntentService.Intent>> list(
        @PathVariable UUID roomId,
        Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.list(customer(authentication), roomId));
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail invalidInstrument(InstrumentMasterException exception) {
        HttpStatus status = "INSTRUMENT_NOT_FOUND".equals(exception.code())
            ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setProperty("code", exception.code());
        return problem;
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer not linked"));
    }
}
