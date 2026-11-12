package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class UpstoxGitHubProgressReaderHttpTest {
    private static final String ROOT =
            "https://api.github.com/repos/operationReact/AiTradingSystem";
    private static final String PULLS =
            ROOT + "/pulls?state=all&sort=updated&direction=desc&per_page=12";
    private static final Instant NOW = Instant.parse("2026-11-13T00:30:00Z");

    @Test
    void requestsExactRepoUrlsAndReadsRealSample() {
        var builder = RestClient.builder();
        var mock = MockRestServiceServer.bindTo(builder).build();
        mock.expect(requestTo(PULLS)).andRespond(withSuccess(
                "[{\"number\":939,\"title\":\"feat(upstox): progress\","
                + "\"state\":\"open\",\"merged_at\":null}]", MediaType.APPLICATION_JSON));
        mock.expect(requestTo(ROOT + "/actions/runs?per_page=15"))
                .andRespond(withSuccess(
                        "{\"workflow_runs\":[{\"status\":\"completed\","
                        + "\"conclusion\":\"success\"}]}",
                        MediaType.APPLICATION_JSON));
        var result = new UpstoxGitHubProgressReader("", builder.build()).inspect(NOW);
        mock.verify();
        assertThat(result.checked()).isEqualTo(1L);
        assertThat(result.details().get("recentCiPassed")).isEqualTo(1L);
    }

    @Test
    void acceptsValidGithubPagesLargerThanPrevious256KbLimit() {
        var builder = RestClient.builder();
        var mock = MockRestServiceServer.bindTo(builder).build();
        // Real 40-PR GitHub pages are over 700 KB. A bounded 12-PR page
        // can also exceed 256 KB when descriptions are long.
        String longDescription = "x".repeat(280_000);
        String body = "[{\\"number\\":940,\\"title\\":\\"fix(upstox): safe progress\\","
                + "\\"state\\":\\"closed\\",\\"merged_at\\":\\"2026-11-13T00:00:00Z\\","
                + "\\"body\\":\\"" + longDescription + "\\"}]";
        mock.expect(requestTo(PULLS))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        mock.expect(requestTo(ROOT + "/actions/runs?per_page=15"))
                .andRespond(withSuccess("{\\"workflow_runs\\":[]}", MediaType.APPLICATION_JSON));
        var result = new UpstoxGitHubProgressReader("", builder.build()).inspect(NOW);
        mock.verify();
        assertThat(result.verified()).isEqualTo(1);
    }

    @Test
    void reportsExcessivelyLargePagesWithSpecificSafeCode() {
        var builder = RestClient.builder();
        var mock = MockRestServiceServer.bindTo(builder).build();
        mock.expect(requestTo(PULLS))
                .andRespond(withSuccess("x".repeat(400_000), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new UpstoxGitHubProgressReader("", builder.build()).inspect(NOW))
                .isInstanceOf(UpstoxGitHubProgressReader.GitHubReadFailure.class)
                .hasMessage("GITHUB_RESPONSE_TOO_LARGE");
        mock.verify();
    }

    @Test
    void classifiesHttpRateLimitWithoutExposingProviderText() {
        var builder = RestClient.builder();
        var mock = MockRestServiceServer.bindTo(builder).build();
        mock.expect(requestTo(PULLS)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .header("X-RateLimit-Remaining", "0").body("private upstream details"));
        assertThatThrownBy(() -> new UpstoxGitHubProgressReader("", builder.build()).inspect(NOW))
                .isInstanceOf(UpstoxGitHubProgressReader.GitHubReadFailure.class)
                .hasMessage("GITHUB_RATE_LIMITED");
        mock.verify();
    }
}
