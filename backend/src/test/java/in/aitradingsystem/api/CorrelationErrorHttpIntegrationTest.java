package in.aitradingsystem.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class CorrelationErrorHttpIntegrationTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void correlationIdTravelsThroughRealMvcRequestAndErrorEnvelope() throws Exception {
        mvc.perform(get("/probe").header(CorrelationIdFilter.HEADER, "e2e-request-42"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(CorrelationIdFilter.HEADER, "e2e-request-42"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/probe"))
                .andExpect(jsonPath("$.correlationId").value("e2e-request-42"));
    }

    @Test
    void invalidCallerIdIsReplacedAtHttpBoundary() throws Exception {
        mvc.perform(get("/ok").header(CorrelationIdFilter.HEADER, "unsafe id"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER, not(blankOrNullString())));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        String probe() { throw new IllegalArgumentException("invalid probe"); }

        @GetMapping("/ok")
        String ok() { return "ok"; }
    }
}
