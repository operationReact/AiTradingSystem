package in.aitradingsystem.news;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import in.aitradingsystem.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

class GeminiConnectivityProbeSecurityIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;

    @Test
    void deniesUnauthenticatedCustomerAndOperatorWithoutCallingProvider() throws Exception {
        String path = "/api/admin/market-intelligence/gemini-connectivity";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("X-API-Key", API_KEY)).andExpect(status().isForbidden());
        for (String role : new String[]{"CUSTOMER", "OPERATOR"}) {
            mvc.perform(post(path).with(SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.issuer("https://test.example").subject("gemini-probe-it"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isForbidden());
        }
    }
}
