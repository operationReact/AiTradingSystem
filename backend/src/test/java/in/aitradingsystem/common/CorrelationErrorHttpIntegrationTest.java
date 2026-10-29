package in.aitradingsystem.common;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
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
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
    }

    @Test
    void correlationIdTravelsThroughHttpRequestAndNormalizedErrorEnvelope() throws Exception {
        mvc.perform(get("/probe").header(CorrelationIdFilter.HEADER, "e2e.request_42"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string(CorrelationIdFilter.HEADER, "e2e.request_42"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.path").value("/probe"))
            .andExpect(jsonPath("$.message").value("Invalid request"))
            .andExpect(jsonPath("$.detail").value("Invalid request"))
            .andExpect(jsonPath("$.error").value("Bad Request"))
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.correlationId").value("e2e.request_42"));
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
        String probe() { throw new IllegalArgumentException("must-not-leak"); }

        @GetMapping("/ok")
        String ok() { return "ok"; }
    }
}
