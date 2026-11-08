package in.aitradingsystem.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AngelOneClientConfigTest {
    @Test
    void loginCarriesOfficialNetworkHeadersFromConfiguredEc2Identity() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://apiconnect.angelone.in");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AngelOneClientConfig.withClientNetworkHeaders(builder, "10.87.0.10",
                "3.108.130.17", "0a:1b:2c:3d:4e:5f");
        server.expect(requestTo("https://apiconnect.angelone.in/rest/auth/angelbroking/user/v1/loginByPassword"))
                .andExpect(header("X-ClientLocalIP", "10.87.0.10"))
                .andExpect(header("X-ClientPublicIP", "3.108.130.17"))
                .andExpect(header("X-MACAddress", "0a:1b:2c:3d:4e:5f"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        RestClient rest = builder.build();
        rest.post().uri("/rest/auth/angelbroking/user/v1/loginByPassword")
                .retrieve().body(String.class);
        server.verify();
    }

    @Test
    void rejectsPartiallyConfiguredOrInvalidNetworkIdentity() {
        var builder = RestClient.builder();
        assertThatThrownBy(() -> AngelOneClientConfig.withClientNetworkHeaders(builder,
                "10.87.0.10", "3.108.130.17", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AngelOneClientConfig.withClientNetworkHeaders(builder,
                "10.87.0.10", "not-an-ip", "0a:1b:2c:3d:4e:5f"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AngelOneClientConfig.withClientNetworkHeaders(builder,
                "10.87.0.10", "3.108.130.17", "00:00:00:00:00:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unrelatedLocalEnvironmentsNeedNoEc2Identity() {
        var builder = RestClient.builder();
        AngelOneClientConfig.withClientNetworkHeaders(builder, "", "", "");
    }
}
