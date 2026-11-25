package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Read-only broker FULL quote provenance window. This must NEVER expand
 * risk-engine order hours or historical candle slot expectations.
 *
 * NSE's Closing Auction Session for eligible cash equities can publish
 * additional prices after the 15:30 continuous-session boundary. The FULL
 * response is still just a broker quote; it is NOT proof of an official close.
 */
public final class VerifiedFullQuoteSession {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime REGULAR_CLOSE = LocalTime.of(15, 30);
    private static final LocalTime POST_CLOSE_END = LocalTime.of(16, 0);

    private VerifiedFullQuoteSession() {}

    public static boolean accepts(String exchange, Instant exchangeAt, Instant observedAt,
            MarketSessionCalendar calendar) {
        if (exchangeAt == null || observedAt == null || exchangeAt.isAfter(observedAt)) return false;
        // Isolated legacy tests can have no calendar; actual backend always injects one.
        if (calendar == null) return true;
        try {
            if (calendar.isInsideCashSession(exchangeAt)) return true;
            if (!"NSE".equals(exchange)) return false;
            var local = exchangeAt.atZone(IST);
            var time = local.toLocalTime();
            if (time.isBefore(REGULAR_CLOSE) || !time.isBefore(POST_CLOSE_END)) return false;
            // Do not promote a holiday fetch time or special Muhurat close to a trade.
            var session = calendar.session(local.toLocalDate());
            return session.tradingDay()
                && session.close().toLocalTime().equals(REGULAR_CLOSE);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
