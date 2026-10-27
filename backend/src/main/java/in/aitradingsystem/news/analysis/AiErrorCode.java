package in.aitradingsystem.news.analysis;

/** Stable, non-sensitive error codes persisted with failed analyses and shown in provider status. */
public enum AiErrorCode {
    UNCONFIGURED,
    TIMEOUT,
    RATE_LIMITED,
    HTTP_ERROR,
    INVALID_RESPONSE
}
