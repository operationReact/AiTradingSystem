package in.aitradingsystem.mutualfund;

import java.time.LocalDate;
import java.time.YearMonth;

/** Deterministic Indian-calendar schedule arithmetic; never a debit authorization. */
public final class MutualFundScheduleRules {
    private MutualFundScheduleRules() {}

    public static LocalDate firstDue(LocalDate today, int sipDay) {
        checkDay(sipDay);
        LocalDate candidate = YearMonth.from(today).atDay(sipDay);
        return candidate.isBefore(today) ? candidate.plusMonths(1) : candidate;
    }

    public static boolean isDue(LocalDate today, LocalDate firstDueOn, int sipDay) {
        checkDay(sipDay);
        return firstDueOn != null && !today.isBefore(firstDueOn)
            && today.getDayOfMonth() == sipDay;
    }

    public static void checkPlan(String isin, String schemeName, long amountPaise,
                                 long monthlyCapPaise, int sipDay) {
        checkDay(sipDay);
        if (isin == null || !isin.matches("INF[A-Z0-9]{9}")
                || schemeName == null || schemeName.isBlank()
                || schemeName.trim().length() < 3 || schemeName.length() > 160
                || amountPaise < 10_000 || amountPaise > 10_000_000
                || monthlyCapPaise < amountPaise || monthlyCapPaise > 10_000_000) {
            throw new IllegalArgumentException("Invalid mutual-fund draft plan");
        }
    }

    private static void checkDay(int day) {
        if (day < 1 || day > 28) throw new IllegalArgumentException("SIP day must be 1-28");
    }
}
