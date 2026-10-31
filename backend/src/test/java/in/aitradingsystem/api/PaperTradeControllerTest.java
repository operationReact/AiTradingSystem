package in.aitradingsystem.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.common.ApiExceptionHandler;
import in.aitradingsystem.paper.PaperQueryService;
import in.aitradingsystem.paper.PaperTradingService;
import in.aitradingsystem.security.UserSessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PaperTradeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class PaperTradeControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PaperTradingService trading;
    @MockitoBean PaperQueryService queries;
    @MockitoBean UserSessionStore sessions;

    @Test
    void rejectsMissingIdempotencyKey() throws Exception {
        mvc.perform(post("/api/paper-trades/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbol\":\"RELIANCE\",\"side\":\"BUY\",\"quantity\":10,\"entryPrice\":500,\"stopLoss\":490,\"target\":530,\"signalGeneratedAt\":\"2026-10-27T10:00:00Z\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBuyWithoutStopLossAndTarget() throws Exception {
        mvc.perform(post("/api/paper-trades/simulate")
                .header("Idempotency-Key", "paper-test-key-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbol\":\"RELIANCE\",\"side\":\"BUY\",\"quantity\":10,\"entryPrice\":500,\"signalGeneratedAt\":\"2026-10-27T10:00:00Z\"}"))
            .andExpect(status().isBadRequest());
    }
}
