package in.aitradingsystem.backtest;

public class BacktestException extends RuntimeException {
    private final String code;

    public BacktestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}