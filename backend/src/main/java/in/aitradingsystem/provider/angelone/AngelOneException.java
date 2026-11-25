package in.aitradingsystem.provider.angelone;

/**
 * Failure from the Angel One integration. The message never contains credentials or tokens.
 */
public class AngelOneException extends RuntimeException {

    public enum Kind { NOT_CONFIGURED, SESSION_EXPIRED, RATE_LIMITED, UNAVAILABLE, REJECTED, INVALID_RESPONSE }

    private final Kind kind;
    private final String code;

    public AngelOneException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
