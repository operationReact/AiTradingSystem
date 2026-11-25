package in.aitradingsystem.instrument;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UpstoxBseCrosswalkControllerTest {
    @Test
    void expectedSourceFailureHasSafeTypedResponseWithoutSourceDetails() {
        var controller = new UpstoxBseCrosswalkController(null);
        var error = new InstrumentMasterException(
            "UPSTOX_BSE_SOURCE_UNAVAILABLE", "Private source diagnostic not exposed");
        var response = controller.sourceError(error);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry(
            "code", "UPSTOX_BSE_SOURCE_UNAVAILABLE");
        assertThat(response.getBody().getDetail())
            .doesNotContain("Private source diagnostic");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }
}
