package in.aitradingsystem.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import in.aitradingsystem.news.NewsRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
@AutoConfigureMockMvc
class PaperTradeControllerTest {
 @Autowired MockMvc mvc;
 @MockitoBean NewsRepository newsRepository;
 @Test void rejectsRiskAboveBudget() throws Exception { mvc.perform(post("/api/paper-trades/simulate").contentType(MediaType.APPLICATION_JSON).content("{\"symbol\":\"RELIANCE\",\"side\":\"BUY\",\"quantity\":100,\"entryPrice\":500,\"stopLoss\":490,\"target\":530}")).andExpect(status().isBadRequest()); }
 @Test void acceptsSmallPaperTrade() throws Exception { mvc.perform(post("/api/paper-trades/simulate").contentType(MediaType.APPLICATION_JSON).content("{\"symbol\":\"RELIANCE\",\"side\":\"BUY\",\"quantity\":10,\"entryPrice\":500,\"stopLoss\":490,\"target\":530}")).andExpect(status().isOk()); }
}
