package in.aitradingsystem.backtest;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import java.math.BigDecimal;
import java.time.LocalDate;

public record WalkForwardRequest(
        long instrumentId,
        HistoricalCandleInterval interval,
        LocalDate fromDate,
        LocalDate toDate,
        int trainingWindowDays,
        int validationWindowDays,
        int stepDays,
        BigDecimal initialCapital) {}
