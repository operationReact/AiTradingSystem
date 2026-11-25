package in.aitradingsystem.execution;

public class ExecutionNotReadyException extends IllegalStateException {
    private final String reasonCode;

    public ExecutionNotReadyException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
