package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UpstoxGitHubProgressReaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void summarizesBoundedRelevantPrSampleAndRecentChecks() throws Exception {
        var prs = JSON.readTree("""
          [
            {"number":917,"title":"feat(upstox): multi timeframe source","state":"open","merged_at":null},
            {"number":922,"title":"feat(upstox): scanner bridge","state":"closed","merged_at":"2026-11-13T00:01:00Z"},
            {"number":923,"title":"fix(portfolio): balances","state":"open","merged_at":null}
          ]""");
        var runs = JSON.readTree("""
          {"workflow_runs":[
            {"status":"completed","conclusion":"success"},
            {"status":"completed","conclusion":"failure"},
            {"status":"in_progress","conclusion":null}
          ]}""");
        var result = UpstoxGitHubProgressReader.summarize(
                prs, runs, Instant.parse("2026-11-13T00:30:00Z"));
        assertThat(result.checked()).isEqualTo(2);
        assertThat(result.verified()).isEqualTo(1);
        assertThat(result.issues()).isEqualTo(1);
        assertThat(result.details().get("scope")).isEqualTo("RECENT_SAMPLE_NOT_TOTAL");
        assertThat(result.details().get("recentCiPassed")).isEqualTo(1L);
        assertThat(result.details().get("recentCiFailed")).isEqualTo(1L);
        assertThat(result.details().get("trackedPrs")).asList().hasSize(2);
        assertThat(result.details().toString()).doesNotContain("token");
    }

    @Test
    void rejectsMalformedOrOversizedGithubTelemetry() throws Exception {
        var now = Instant.parse("2026-11-13T00:30:00Z");
        var validRuns = JSON.readTree("{\"workflow_runs\":[]}");
        var invalidPrs = JSON.readTree("{\"secret\":\"oops\"}");
        assertThatThrownBy(() -> UpstoxGitHubProgressReader.summarize(
                invalidPrs, validRuns, now)).isInstanceOf(IllegalArgumentException.class);
    }
}
