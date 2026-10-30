package in.aitradingsystem.analytics;

import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/** Inclusive dates, at most 366 calendar days; all event queries use the same IST half-open interval. */
public record AnalyticsPeriod(LocalDate from, LocalDate to) {
    public AnalyticsPeriod {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 365) {
            throw new IllegalArgumentException("An ordered analytics range of 1–366 days is required");
        }
    }
    public OffsetDateTime start() { return from.atStartOfDay(IndianCashMarketCalendar.ZONE).toOffsetDateTime(); }
    public OffsetDateTime endExclusive() { return to.plusDays(1).atStartOfDay(IndianCashMarketCalendar.ZONE).toOffsetDateTime(); }
    public int days() { return Math.toIntExact(ChronoUnit.DAYS.between(from, to) + 1); }
}
