package in.aitradingsystem.marketdata;

import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only whole-market NSE board for authenticated MintTick customers. No broker credentials,
 * symbol tokens or order capability are exposed; every row carries its own exchange timestamp.
 */
@RestController
@Validated
@RequestMapping("/api/customer/market-data/board")
public class CustomerMarketBoardController {
    private final MarketBoardService board;
    private final CustomerIdentityStore identities;

    public CustomerMarketBoardController(MarketBoardService board, CustomerIdentityStore identities) {
        this.board = board;
        this.identities = identities;
    }

    @GetMapping("/summary")
    public ResponseEntity<MarketBoardService.Summary> summary(Authentication authentication) {
        requireCustomer(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(board.summary());
    }

    @GetMapping
    public ResponseEntity<MarketBoardService.Board> board(Authentication authentication,
            @RequestParam(defaultValue = "ALL") @Pattern(regexp = "ALL|NIFTY50|LARGE|MID|SMALL|MICRO") String segment,
            @RequestParam(defaultValue = "GAINERS")
                @Pattern(regexp = "CHANGE|GAINERS|LOSERS|ACTIVE|VOLUME|HIGH52|LOW52|GAP_UP|GAP_DOWN") String sort,
            @RequestParam(required = false) @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9 &,.()/-]*") String sector,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        requireCustomer(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(board.board(
            MarketBoardService.parseSegment(segment), MarketBoardService.parseSort(sort),
            sector == null || sector.isBlank() ? null : sector.trim(), limit));
    }

    private void requireCustomer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        boolean active = identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role())).isPresent();
        if (!active) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(MarketDataException.class)
    ProblemDetail invalid(MarketDataException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid market board request");
        problem.setProperty("code", ex.code());
        return problem;
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail unavailable() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
            "Market board is unavailable");
        problem.setProperty("code", "MARKET_BOARD_UNAVAILABLE");
        return problem;
    }
}
