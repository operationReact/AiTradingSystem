package in.aitradingsystem.mutualfund;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AmfiDailyNavSourceTest {
    private static final LocalDate TODAY = LocalDate.of(2026,11,18);
    private static String valid() {
        return """
            Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date
            120716;INF789F01XA0;-;UTI Nifty 50 Index Fund;Direct Plan;Growth;178.8713;17-Nov-2026
            120684;INF109K01Y80;-;ICICI Prudential Nifty Next 50 Index Fund;Direct Plan;Growth;67.6780;17-Nov-2026
            122639;INF879O01027;-;Parag Parikh Flexi Cap Fund;Direct Plan;Growth;94.2890;17-Nov-2026
            118834;INF769K01BI1;-;Mirae Asset Large & Midcap Fund;Direct Plan;Growth;181.225;17-Nov-2026
            118989;INF179K01XQ0;-;HDFC Mid Cap Fund;Direct Plan;Growth Option;230.556;17-Nov-2026
            118778;INF204K01K15;-;Nippon India Small Cap Fund;Direct Plan;Growth Option;202.7762;17-Nov-2026
            119835;INF200K01RA0;-;SBI CONTRA FUND;Direct Plan;Growth;422.5725;17-Nov-2026
            118968;INF179K01WA6;-;HDFC Balanced Advantage Fund;Direct Plan;Growth Option;583.374;17-Nov-2026
            120197;INF109K01Q49;-;ICICI Prudential Liquid Fund -;Direct Plan;Growth;424.9422;17-Nov-2026
            """;
    }

    @Test void acceptsNineOfficialDirectGrowthSchemes() {
        var parsed = AmfiDailyNavSource.parse(valid(), TODAY);
        assertEquals(9,parsed.size());
        assertEquals("178.8713",parsed.get(120716).nav().toPlainString());
    }

    @Test void rejectsIsinSubstitutionIdcwAndIncompleteFeed() {
        assertThrows(IllegalArgumentException.class, () ->
            AmfiDailyNavSource.parse(valid().replace("INF789F01XA0","INF789F01JN2"),TODAY));
        assertThrows(IllegalArgumentException.class, () ->
            AmfiDailyNavSource.parse(valid().replace("Direct Plan;Growth;94.2890",
                "Direct Plan;IDCW;94.2890"),TODAY));
        assertThrows(IllegalArgumentException.class, () ->
            AmfiDailyNavSource.parse(valid().replace("120197;INF109K01Q49;", "999999;INF109K01Q49;"),TODAY));
    }

    @Test void rejectsStaleOrFutureNavWithoutManufacturingAlert() {
        assertThrows(IllegalArgumentException.class, () ->
            AmfiDailyNavSource.parse(valid(), TODAY.plusDays(12)));
        assertThrows(IllegalArgumentException.class, () ->
            AmfiDailyNavSource.parse(valid(), TODAY.minusDays(2)));
    }
}
