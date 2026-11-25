package in.aitradingsystem.provider.upstox;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real Spring startup regression test: the explicitly enabled secured probe
 * must initialize under method-security AOP, without touching SSM or Upstox.
 * The existing default-disabled suite cannot detect a final-controller proxy
 * startup failure on the PAPER host.
 */
@TestPropertySource(properties = {
        "minttick.upstox-analytics.probe-enabled=true",
        "MINTTICK_UPSTOX_ANALYTICS_ENABLED=false"
})
class UpstoxAnalyticsEnabledContextIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;

    @Test
    void securedProbeStartsWhenFlagEnabledAndDoesNotAccessProvider() throws Exception {
        mvc.perform(post("/api/admin/market-data/upstox-analytics/probe")
                    .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.stored").value(false))
                .andExpect(jsonPath("$.liveTradingEnabled").value(false));

        mvc.perform(post("/api/admin/market-data/upstox-analytics/probe"))
                .andExpect(status().isUnauthorized());
    }
}
