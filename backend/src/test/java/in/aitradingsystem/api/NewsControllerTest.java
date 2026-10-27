package in.aitradingsystem.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import in.aitradingsystem.news.NewsRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = NewsController.class, properties = "NEWS_API_TOKEN=test-news-token")
@AutoConfigureMockMvc(addFilters = false)
@Import({NewsApiTokenFilter.class, in.aitradingsystem.common.ApiExceptionHandler.class})
class NewsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean NewsRepository repository;

    @Test
    void requiresConfiguredBearerToken() throws Exception {
        mvc.perform(get("/api/news")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/news").header("Authorization", "Bearer wrong-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsPaginatedReadOnlyResultsWithinBounds() throws Exception {
        when(repository.recent(21, 0)).thenReturn(List.of());
        mvc.perform(get("/api/news").header("Authorization", "Bearer test-news-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limit").value(20))
            .andExpect(jsonPath("$.offset").value(0))
            .andExpect(jsonPath("$.items").isEmpty());
        verify(repository).recent(21, 0);
    }

    @Test
    void rejectsUnboundedPageSize() throws Exception {
        mvc.perform(get("/api/news?limit=101").header("Authorization", "Bearer test-news-token"))
            .andExpect(status().isBadRequest());
    }
}
