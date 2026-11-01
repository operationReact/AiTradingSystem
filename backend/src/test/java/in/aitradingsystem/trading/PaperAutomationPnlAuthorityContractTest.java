package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPnlAuthorityContractTest {
    @Test
    void productionPnlMustReuseExistingPersistedPortfolioRules() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pnl-authority.md"));
        assertThat(text).contains("existing persisted portfolio/PAPER ledger");
        assertThat(text).contains("existing simulated fees/slippage");
        assertThat(text).contains("unrealized P&L valuation source and freshness rules");
        assertThat(text).contains("portfolio/history consistency after retry/restart");
        assertThat(text).contains("Do not replace mature portfolio calculations with the simplified foundation math");
    }
}
