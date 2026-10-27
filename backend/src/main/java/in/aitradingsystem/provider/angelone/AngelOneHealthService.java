package in.aitradingsystem.provider.angelone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Tracks the last observed provider state. Status reads never call the provider; only testConnection() logs in.
 */
@Service
public class AngelOneHealthService {

    public enum State { NOT_CONFIGURED, CONFIGURED_UNVERIFIED, AUTHENTICATED, SESSION_EXPIRED, DEGRADED, UNAVAILABLE }

    public record ProviderHealth(State state, String lastErrorCode, Instant lastSuccessAt, Instant checkedAt) {}

    private final AngelOneSessionManager session;
    private final Clock clock;

    private State state;
    private String lastErrorCode;
    private Instant lastSuccessAt;
    private Instant checkedAt;

    @Autowired
    public AngelOneHealthService(AngelOneSessionManager session) {
        this(session, Clock.systemUTC());
    }

    AngelOneHealthService(AngelOneSessionManager session, Clock clock) {
        this.session = session;
        this.clock = clock;
        this.state = session.credentialsConfigured() ? State.CONFIGURED_UNVERIFIED : State.NOT_CONFIGURED;
    }

    public synchronized ProviderHealth status() {
        if (!session.credentialsConfigured()) {
            return new ProviderHealth(State.NOT_CONFIGURED, null, null, null);
        }
        return new ProviderHealth(state, lastErrorCode, lastSuccessAt, checkedAt);
    }

    /** Performs one login attempt and records the outcome. */
    public ProviderHealth testConnection() {
        if (!session.credentialsConfigured()) {
            return status();
        }
        try {
            session.login();
            recordSuccess();
        } catch (AngelOneException e) {
            recordFailure(e);
        }
        return status();
    }

    public synchronized void recordSuccess() {
        Instant now = clock.instant();
        state = State.AUTHENTICATED;
        lastErrorCode = null;
        lastSuccessAt = now;
        checkedAt = now;
    }

    public synchronized void recordFailure(AngelOneException e) {
        state = switch (e.kind()) {
            case SESSION_EXPIRED -> State.SESSION_EXPIRED;
            case RATE_LIMITED, INVALID_RESPONSE -> State.DEGRADED;
            case NOT_CONFIGURED -> State.NOT_CONFIGURED;
            case UNAVAILABLE, REJECTED -> State.UNAVAILABLE;
        };
        lastErrorCode = e.code();
        checkedAt = clock.instant();
    }
}
