package in.aitradingsystem.risk;

import org.springframework.http.HttpStatus;

public final class RiskApiException extends RuntimeException {
    private final RiskErrorCode code;
    private final HttpStatus status;

    public RiskApiException(RiskErrorCode code, HttpStatus status, String message) {
        super(message);
        if (code == null || status == null) throw new IllegalArgumentException("risk error code and status are required");
        this.code = code;
        this.status = status;
    }

    public RiskErrorCode code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
