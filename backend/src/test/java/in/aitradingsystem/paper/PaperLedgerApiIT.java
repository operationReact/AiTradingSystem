package in.aitradingsystem.paper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class PaperLedgerApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired MockMvc mvc;

    @Test
    void fillAndCashLedgerReadModelsAreAvailableToOperatorUi() throws Exception {
        mvc.perform(get("/api/paper-fills?limit=10").header(KEY_HEADER, API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/paper-cash-ledger?limit=10").header(KEY_HEADER, API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }
}
