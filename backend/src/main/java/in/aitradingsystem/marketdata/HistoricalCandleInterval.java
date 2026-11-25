package in.aitradingsystem.marketdata;

/** Intervals and maximum inclusive calendar-day window documented by Angel One SmartAPI. */
public enum HistoricalCandleInterval {
    ONE_MINUTE(30),
    THREE_MINUTE(60),
    FIVE_MINUTE(100),
    TEN_MINUTE(100),
    FIFTEEN_MINUTE(200),
    THIRTY_MINUTE(200),
    ONE_HOUR(400),
    ONE_DAY(2_000);

    private final int maxDaysPerRequest;

    HistoricalCandleInterval(int maxDaysPerRequest) {
        this.maxDaysPerRequest = maxDaysPerRequest;
    }

    public int maxDaysPerRequest() {
        return maxDaysPerRequest;
    }
}
