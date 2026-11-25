package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class UpstoxRegisteredIpAssessmentTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void primaryMatchIsReadOnlyAndNeverAuthorizesExecution() throws Exception {
        var report = UpstoxRegisteredIpAssessment.assess(json.readTree("""
            {"primary_ip":"3.108.130.17","secondary_ip":"198.51.100.20"}
            """));
        assertThat(report.expectedEc2Ip()).isEqualTo("3.108.130.17");
        assertThat(report.status()).isEqualTo("REGISTERED_MATCH");
        assertThat(report.registrationMatchesEc2()).isTrue();
        assertThat(report.liveExecutionAuthorized()).isFalse();
    }

    @Test
    void secondaryMatchIsValidButNotExecutionConsent() throws Exception {
        var report = UpstoxRegisteredIpAssessment.assess(json.readTree("""
            {"primary_ip":"192.0.2.10","secondary_ip":"3.108.130.17"}
            """));
        assertThat(report.status()).isEqualTo("REGISTERED_MATCH");
        assertThat(report.liveExecutionAuthorized()).isFalse();
    }

    @Test
    void wrongRegistrationCannotTurnGreen() throws Exception {
        var report = UpstoxRegisteredIpAssessment.assess(json.readTree("""
            {"primary_ip":"192.0.2.10","secondary_ip":"192.0.2.11"}
            """));
        assertThat(report.status()).isEqualTo("REGISTERED_MISMATCH");
        assertThat(report.registrationMatchesEc2()).isFalse();
        assertThat(report.liveExecutionAuthorized()).isFalse();
    }

    @Test
    void missingOrMalformedBrokerResponseNeverClaimsIpMatch() throws Exception {
        for (var body : new String[]{"{}","[]","{\"primary_ip\":null}",
                "{\"primary_ip\":{\"addr\":\"3.108.130.17\"}}"}) {
            var report = UpstoxRegisteredIpAssessment.assess(json.readTree(body));
            assertThat(report.registrationMatchesEc2()).isFalse();
            assertThat(report.liveExecutionAuthorized()).isFalse();
        }
        assertThat(UpstoxRegisteredIpAssessment.assess(null).status())
            .isEqualTo("UNVERIFIED");
    }
}
