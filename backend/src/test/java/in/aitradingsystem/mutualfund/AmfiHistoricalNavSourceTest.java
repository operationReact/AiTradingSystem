package in.aitradingsystem.mutualfund;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AmfiHistoricalNavSourceTest {
    private static final LocalDate FROM=LocalDate.of(2026,11,10);
    private static final LocalDate TO=LocalDate.of(2026,11,16);
    private static String feed() {
        StringBuilder result=new StringBuilder(
            "Scheme Code;Scheme Name;ISIN Div Payout/ISIN Growth;ISIN Div Reinvestment;"
            +"Net Asset Value;Repurchase Price;Sale Price;Date\n");
        for (MfResearchCatalogue.Fund fund: MfResearchCatalogue.FUNDS) {
            for (String day: new String[]{"10-Nov-2026","11-Nov-2026"}) {
                result.append(fund.schemeCode()).append(';').append(fund.name())
                    .append(" - Direct Growth;").append(fund.isin())
                    .append(";;100.5000;;;").append(day).append('\n');
            }
        }
        return result.toString();
    }

    @Test void onlyAcceptsExactIsinAndCompleteTradingDates() {
        var rows=AmfiHistoricalNavSource.parse(feed(),FROM,TO);
        assertEquals(18,rows.size());
        assertEquals(FROM,rows.get(0).navDate());
    }

    @Test void failsClosedOnIsinMismatchAndOutOfRangeDate() {
        assertThrows(IllegalArgumentException.class, () ->
            AmfiHistoricalNavSource.parse(feed().replace("INF789F01XA0","INF789F01JN2"),FROM,TO));
        assertThrows(IllegalArgumentException.class, () ->
            AmfiHistoricalNavSource.parse(feed().replace("11-Nov-2026","22-Nov-2026"),FROM,TO));
        assertThrows(IllegalArgumentException.class, () ->
            AmfiHistoricalNavSource.parse(feed(),FROM,FROM.plusDays(7)));
    }
}
