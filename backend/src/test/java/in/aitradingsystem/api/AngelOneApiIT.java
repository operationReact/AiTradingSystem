package in.aitradingsystem.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import in.aitradingsystem.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class AngelOneApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";
    private static final String BASE = "/api/providers/angel-one";

    @Autowired MockMvc mvc;

    @Test
    void statusRequiresApiKey() throws Exception {
        mvc.perform(get(BASE + "/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void statusReportsStateWithoutSecrets() throws Exception {
        mvc.perform(get(BASE + "/status").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").isString())
            .andExpect(content().string(not(containsString("jwt"))))
            .andExpect(content().string(not(containsString("refresh"))));
    }

    @Test
    void ltpRejectsUnsupportedExchange() throws Exception {
        mvc.perform(get(BASE + "/ltp").param("exchange", "MCX").param("tradingSymbol", "RELIANCE-EQ")
                .param("symbolToken", "2885").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ltpRejectsMalformedSymbolToken() throws Exception {
        mvc.perform(get(BASE + "/ltp").param("exchange", "NSE").param("tradingSymbol", "RELIANCE-EQ")
                .param("symbolToken", "28;85").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ltpRequiresApiKey() throws Exception {
        mvc.perform(get(BASE + "/ltp").param("exchange", "NSE").param("tradingSymbol", "RELIANCE-EQ")
                .param("symbolToken", "2885"))
            .andExpect(status().isUnauthorized());
    }
}
