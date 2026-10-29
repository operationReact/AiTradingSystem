package in.aitradingsystem.marketdata;

import java.time.LocalDate;
import java.time.ZonedDateTime;

public record MarketSession(LocalDate tradeDate, ZonedDateTime open, ZonedDateTime close, boolean tradingDay) {}
