package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Architectural guardrails for the latency-sensitive deterministic risk path. */
class RiskPerformanceContractTest {
    @Test
    void contextFactoryUsesFixedCurrentStateQueryBudget() throws Exception {
        String source = Files.readString(Path.of("src/main/java/in/aitradingsystem/risk/RiskContextFactory.java"));
        long directJdbcQueries = occurrences(source, "jdbc.sql(");

        // Active policy is loaded by RiskPolicyConfigRepository; all other state is bounded here.
        assertThat(directJdbcQueries).isLessThanOrEqualTo(9);
        assertThat(source).doesNotContain("SELECT * FROM paper_fill");
        assertThat(source).doesNotContain("SELECT * FROM paper_order");
    }

    @Test
    void riskRulesRemainPureInMemoryAndCannotAcquireIoDependencies() throws Exception {
        Path rules = Path.of("src/main/java/in/aitradingsystem/risk/rules");
        try (var files = Files.list(rules)) {
            for (Path file : files.filter(path -> path.toString().endsWith("Rule.java")).toList()) {
                String source = Files.readString(file);
                assertThat(source)
                        .as(file.getFileName() + " must remain an in-memory rule")
                        .doesNotContain("JdbcClient", "JdbcTemplate", "DataSource", "Repository",
                                "RestClient", "WebClient", "HttpClient", ".provider.");
            }
        }
    }

    @Test
    void engineContainsNoDatabaseOrNetworkDependency() throws Exception {
        String source = Files.readString(Path.of("src/main/java/in/aitradingsystem/risk/RiskPolicyEngine.java"));
        assertThat(source).doesNotContain("JdbcClient", "JdbcTemplate", "DataSource", "Repository",
                "RestClient", "WebClient", "HttpClient", ".provider.");
    }

    private static long occurrences(String text, String token) {
        long count = 0;
        int from = 0;
        while ((from = text.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }
}
