package in.aitradingsystem.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Execution-mode contract. The backend is the source of truth for the mode.
 * LIVE is never activated in this build: no live order path exists, so every
 * request to switch to LIVE is refused with 409 and the active mode is unchanged.
 */
@RestController
@RequestMapping("/api/system/execution-mode")
public class ExecutionModeController {
    static final String PAPER = "PAPER";
    static final String LIVE = "LIVE";
    static final String LIVE_CONFIRMATION = "SWITCH TO LIVE";
    static final List<String> LIVE_BLOCKERS = List.of(
        "LIVE_ORDER_PATH_NOT_IMPLEMENTED",
        "BROKER_LIVE_CREDENTIALS_NOT_VERIFIED",
        "LIVE_SAFETY_CONDITIONS_NOT_VERIFIED");

    private final String configuredMode;

    public ExecutionModeController(@Value("${app.trading-mode:PAPER}") String configuredMode) {
        this.configuredMode = configuredMode == null ? PAPER : configuredMode.trim().toUpperCase(Locale.ROOT);
    }

    public record ModeView(String activeMode, boolean liveAvailable, List<String> liveBlockers, Instant checkedAt) {}

    public record ModeChangeRequest(@NotBlank @Size(max = 10) String mode, @Size(max = 40) String confirmation) {}

    @GetMapping
    public ModeView current() {
        return view();
    }

    @PutMapping
    public ModeView change(@Valid @RequestBody ModeChangeRequest request) {
        String requested = request.mode().trim().toUpperCase(Locale.ROOT);
        if (PAPER.equals(requested)) {
            return view();
        }
        if (!LIVE.equals(requested)) {
            throw new IllegalArgumentException("Unsupported execution mode: " + requested);
        }
        if (!LIVE_CONFIRMATION.equals(request.confirmation())) {
            throw new IllegalArgumentException("Switching to LIVE requires the confirmation text '" + LIVE_CONFIRMATION + "'.");
        }
        throw new LiveModeUnavailableException();
    }

    private ModeView view() {
        return new ModeView(configuredMode, false, LIVE_BLOCKERS, Instant.now());
    }

    static final class LiveModeUnavailableException extends RuntimeException {
        LiveModeUnavailableException() {
            super("LIVE trading is unavailable in this build. The active mode remains PAPER.");
        }
    }

    @ExceptionHandler(LiveModeUnavailableException.class)
    ProblemDetail liveUnavailable(LiveModeUnavailableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Live mode unavailable");
        pd.setProperty("activeMode", PAPER);
        pd.setProperty("liveBlockers", LIVE_BLOCKERS);
        return pd;
    }
}
