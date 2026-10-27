package in.aitradingsystem.news.analysis;

/** Provider failure with a safe, stable code. The message never contains credentials or raw model output. */
public class AiProviderException extends RuntimeException {
    private final AiErrorCode code;

    public AiProviderException(AiErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AiErrorCode code() {
        return code;
    }

    /** Only transient failures are retried. */
    public boolean retryable() {
        return code == AiErrorCode.TIMEOUT || code == AiErrorCode.RATE_LIMITED;
    }
}
