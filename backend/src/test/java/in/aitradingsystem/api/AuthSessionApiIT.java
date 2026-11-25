package in.aitradingsystem.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@TestPropertySource(properties = {
    "app.security.dev-session-bootstrap-enabled=true",
    "app.security.session.cookie-secure=false"
})
class AuthSessionApiIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void apiKeyCanBootstrapRevocableHttpOnlySessionAndSessionMutationsRequireCsrf() throws Exception {
        MvcResult issued = mvc.perform(post("/api/auth/dev/session")
                .header("X-API-Key", AbstractPostgresIT.API_KEY))
            .andExpect(status().isOk())
            .andExpect(header().string("Set-Cookie", containsString("ATS_SESSION=")))
            .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
            .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
            .andExpect(jsonPath("$.authenticationType").value("SESSION"))
            .andExpect(jsonPath("$.csrfToken").isNotEmpty())
            .andReturn();

        Cookie sessionCookie = issued.getResponse().getCookie("ATS_SESSION");
        JsonNode body = objectMapper.readTree(issued.getResponse().getContentAsString());
        String csrf = body.get("csrfToken").asText();

        mvc.perform(get("/api/auth/session").cookie(sessionCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").isNotEmpty())
            .andExpect(jsonPath("$.authenticationType").value("SESSION"));

        mvc.perform(delete("/api/auth/session").cookie(sessionCookie))
            .andExpect(status().isForbidden());

        mvc.perform(delete("/api/auth/session").cookie(sessionCookie).header("X-CSRF-Token", csrf))
            .andExpect(status().isNoContent())
            .andExpect(header().string("Set-Cookie", containsString("ATS_SESSION=;")));

        mvc.perform(get("/api/auth/session").cookie(sessionCookie))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCsrfTokenIsRejectedWithoutRevokingSession() throws Exception {
        MvcResult issued = mvc.perform(post("/api/auth/dev/session")
                .header("X-API-Key", AbstractPostgresIT.API_KEY))
            .andExpect(status().isOk())
            .andReturn();
        Cookie sessionCookie = issued.getResponse().getCookie("ATS_SESSION");

        mvc.perform(delete("/api/auth/session").cookie(sessionCookie).header("X-CSRF-Token", "wrong"))
            .andExpect(status().isForbidden());

        mvc.perform(get("/api/auth/session").cookie(sessionCookie))
            .andExpect(status().isOk());
    }
}
