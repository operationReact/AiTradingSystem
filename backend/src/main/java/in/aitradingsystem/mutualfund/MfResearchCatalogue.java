package in.aitradingsystem.mutualfund;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Verified AMFI scheme code + ISIN, Direct Plan / Growth option, 2026-11-18. */
public final class MfResearchCatalogue {
    public record Fund(int schemeCode, String isin, String name, String category,
                       String risk, int dipThresholdBps, int illustrativeAmountInr) {}

    public static final List<Fund> FUNDS = List.of(
        new Fund(120716, "INF789F01XA0", "UTI Nifty 50 Index Fund", "Nifty 50 index", "HIGH", 400, 1000),
        new Fund(120684, "INF109K01Y80", "ICICI Prudential Nifty Next 50 Index Fund", "Nifty Next 50 index", "VERY_HIGH", 600, 500),
        new Fund(122639, "INF879O01027", "Parag Parikh Flexi Cap Fund", "Flexi cap", "VERY_HIGH", 500, 750),
        new Fund(118834, "INF769K01BI1", "Mirae Asset Large & Midcap Fund", "Large & mid cap", "VERY_HIGH", 700, 500),
        new Fund(118989, "INF179K01XQ0", "HDFC Mid Cap Fund", "Mid cap", "VERY_HIGH", 800, 500),
        new Fund(118778, "INF204K01K15", "Nippon India Small Cap Fund", "Small cap", "VERY_HIGH", 1000, 250),
        new Fund(119835, "INF200K01RA0", "SBI CONTRA FUND", "Contra", "VERY_HIGH", 600, 500),
        new Fund(118968, "INF179K01WA6", "HDFC Balanced Advantage Fund", "Dynamic asset allocation", "HIGH", 400, 500),
        new Fund(120197, "INF109K01Q49", "ICICI Prudential Liquid Fund -", "Liquid", "LOW_TO_MODERATE", 0, 0)
    );

    public static final Map<Integer, Fund> BY_CODE = FUNDS.stream()
        .collect(Collectors.toUnmodifiableMap(Fund::schemeCode, Function.identity()));

    private MfResearchCatalogue() {}
}
