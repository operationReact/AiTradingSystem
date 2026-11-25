package in.aitradingsystem.instrument;

public class InstrumentMasterException extends RuntimeException {
    private final String code;

    public InstrumentMasterException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
