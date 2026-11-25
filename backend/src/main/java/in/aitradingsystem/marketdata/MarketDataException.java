package in.aitradingsystem.marketdata;

public class MarketDataException extends RuntimeException {
    private final String code;

    public MarketDataException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
