package in.aitradingsystem.provider.angelone;

import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Status reads do not call SmartAPI; the explicit connection test performs one login request. */
@Service
public class AngelOneHealthService {
    public enum State { NOT_CONFIGURED, CONFIGURED_UNVERIFIED, AUTHENTICATED, SESSION_EXPIRED, DEGRADED, UNAVAILABLE }

    public record ProviderHealth(State state, String lastErrorCode, Instant lastSuccessAt, Instant lastQuoteAt,
                                 Instant checkedAt, boolean sessionActive, Long sessionAgeSeconds) {}

    private final AngelOneSessionManager session;
    private final Clock clock;
    private State state;
    private String lastErrorCode;
    private Instant lastSuccessAt;
    private Instant lastQuoteAt;
    private Instant checkedAt;

    @Autowired
    public AngelOneHealthService(AngelOneSessionManager session) {
        this(session, Clock.systemUTC());
    }

    AngelOneHealthService(AngelOneSessionManager session, Clock clock) {
        this.session = session;
        this.clock = clock;
        state = session.credentialsConfigured() ? State.CONFIGURED_UNVERIFIED : State.NOT_CONFIGURED;
    }

    public synchronized ProviderHealth status() {
        if (!session.credentialsConfigured()) {
            return new ProviderHealth(State.NOT_CONFIGURED, null, null, null, null, false, null);
        }
        boolean active = session.hasActiveSession();
        State visibleState = state == State.AUTHENTICATED && !active ? State.CONFIGURED_UNVERIFIED : state;
        return new ProviderHealth(visibleState, lastErrorCode, lastSuccessAt, lastQuoteAt, checkedAt,
                active, session.sessionAgeSeconds());
    }

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

    public synchronized void recordQuoteSuccess() {
        Instant now = clock.instant();
        state = State.AUTHENTICATED;
        lastErrorCode = null;
        lastSuccessAt = now;
        lastQuoteAt = now;
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
