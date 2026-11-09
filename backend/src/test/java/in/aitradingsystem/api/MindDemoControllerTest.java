package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MindDemoControllerTest {
    private final MindDemoController controller = new MindDemoController();

    @Test
    void allTenAgentScreensExposeClearlyMarkedNonExecutableData() {
        List<Map<String,Object>> pages = List.of(
            controller.hub(),controller.council(),controller.evidence(),
            controller.weights(),controller.scorecard(),controller.calibration(),
            controller.shadow(),controller.institutionalFlow(),
            controller.dailyVerdict(),controller.incidents());
        assertEquals(10, pages.size());
        for (Map<String,Object> page : pages) {
            assertEquals(Boolean.TRUE, page.get("demo"));
            assertEquals(Boolean.FALSE, page.get("verified"));
            assertEquals(Boolean.FALSE, page.get("executionEnabled"));
            assertEquals("SYNTHETIC_OFFLINE_FIXTURES", page.get("source"));
            assertFalse(page.get("data") == null);
        }
    }

    @Test
    void dataUncertainRegimeForcesAbstention() {
        Map<?,?> data = (Map<?,?>)controller.weights().get("data");
        Map<?,?> regimes = (Map<?,?>)data.get("regimes");
        assertEquals(List.of(0,0,0,0), regimes.get("Data Uncertain"));
        assertEquals("ABSTAIN", data.get("unknownDataAction"));
    }

    @Test
    void institutionalValuesAreExplicitlyUnverified() {
        Map<?,?> data = (Map<?,?>)controller.institutionalFlow().get("data");
        assertEquals(false,data.get("officialSourceConnected"));
        assertTrue(data.get("warning").toString().contains("invented"));
    }
}
