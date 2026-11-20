package in.aitradingsystem.mutualfund;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MfResearchSignalRulesTest {
    private final MfResearchCatalogue.Fund fund = MfResearchCatalogue.BY_CODE.get(120716);

    @Test void onlySignalsOnFirstThresholdCrossingWithEnoughHistory() {
        List<MfResearchSignalRules.Observation> navs = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            navs.add(new MfResearchSignalRules.Observation(LocalDate.of(2026, 10, 1).plusDays(i),
                new BigDecimal("100")));
        }
        assertNull(MfResearchSignalRules.evaluate(fund, navs));
        navs.add(new MfResearchSignalRules.Observation(LocalDate.of(2026, 10, 22),
            new BigDecimal("95")));
        var signal = MfResearchSignalRules.evaluate(fund, navs);
        assertNotNull(signal);
        assertEquals("PULLBACK_REVIEW", signal.kind());
        assertEquals(500, signal.drawdownBps());
        navs.add(new MfResearchSignalRules.Observation(LocalDate.of(2026, 10, 23),
            new BigDecimal("94")));
        assertNull(MfResearchSignalRules.evaluate(fund, navs)); // no daily duplicate
        navs.add(new MfResearchSignalRules.Observation(LocalDate.of(2026, 10, 24),
            new BigDecimal("91")));
        assertNotNull(MfResearchSignalRules.evaluate(fund, navs)); // new deep crossing
    }

    @Test void liquidFundDoesNotGenerateDipBuyAlerts() {
        var liquid = MfResearchCatalogue.BY_CODE.get(120197);
        var observations = new ArrayList<MfResearchSignalRules.Observation>();
        for (int i=0;i<24;i++) {
            observations.add(new MfResearchSignalRules.Observation(
                LocalDate.of(2026,10,1).plusDays(i),
                i < 22 ? new BigDecimal("100") : new BigDecimal("50")));
        }
        assertNull(MfResearchSignalRules.evaluate(liquid,observations));
    }
}
