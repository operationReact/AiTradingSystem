package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

/** Real provider contract shape, mocked HTTP only: never source-rights evidence. */
class UpstoxFnoExpiryDiscoveryTest {
    private static final Instant NOW=Instant.parse("2026-11-15T00:00:00Z");
    private final SsmClient ssm=mock(SsmClient.class);
    private final RestClient.Builder builder=RestClient.builder().baseUrl("https://api.upstox.com");
    private final MockRestServiceServer http=MockRestServiceServer.bindTo(builder).build();
    private final UpstoxAnalyticsSource source=new UpstoxAnalyticsSource(true,ssm,builder.build());

    private void token() {
        when(ssm.getParameter(any(software.amazon.awssdk.services.ssm.model.GetParameterRequest.class)))
            .thenReturn(GetParameterResponse.builder().parameter(Parameter.builder()
                .name("/minttick/upstox/analytics-token")
                .type(ParameterType.SECURE_STRING).value("hidden-test-only").build()).build());
    }
    private static String rows(String expiry) {
        return """
            {"status":"success","data":[
              {"underlying_key":"NSE_INDEX|Nifty 50","segment":"NSE_FO","exchange":"NSE",
               "instrument_key":"NSE_FO|61001","expiry":"%s","instrument_type":"CE",
               "strike_price":26000,"tick_size":5,"lot_size":65},
              {"underlying_key":"NSE_INDEX|Nifty 50","segment":"NSE_FO","exchange":"NSE",
               "instrument_key":"NSE_FO|61002","expiry":"%s","instrument_type":"PE",
               "strike_price":26000,"tick_size":5,"lot_size":65}
            ]}
            """.formatted(expiry,expiry);
    }
    private void expect(String keyword,String body) {
        http.expect(requestTo(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("/v2/option/contract?"),
                org.hamcrest.Matchers.containsString("expiry_date="+keyword))))
            .andExpect(method(org.springframework.http.HttpMethod.GET))
            .andExpect(header("Authorization","Bearer hidden-test-only"))
            .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
    }

    @Test
    void derivesUniqueSortedProviderListedExpiriesWithoutGuessedCalendarOrStorage() {
        token();
        String[] periods={"current_week","next_week","far_week",
                          "current_month","next_month","far_month"};
        String[] expiries={"2026-11-17","2026-11-24","2026-12-01",
                           "2026-11-24","2026-12-29","2027-01-26"};
        for(int i=0;i<periods.length;i++)expect(periods[i],rows(expiries[i]));
        assertThat(source.currentNiftyOptionExpiries(NOW)).containsExactly(
            LocalDate.parse("2026-11-17"),LocalDate.parse("2026-11-24"),
            LocalDate.parse("2026-12-01"),LocalDate.parse("2026-12-29"),
            LocalDate.parse("2027-01-26"));
        verify(ssm,times(6)).getParameter(any(software.amazon.awssdk.services.ssm.model.GetParameterRequest.class));
        http.verify();
    }

    @Test
    void emptyRelativeBucketDoesNotSuppressOtherListedExpiries() {
        token();
        String empty="{\"status\":\"success\",\"data\":[]}";
        expect("current_week",empty);
        expect("next_week",rows("2026-11-24"));
        expect("far_week",empty);
        expect("current_month",rows("2026-11-24"));
        expect("next_month",empty);
        expect("far_month",empty);
        assertThat(source.currentNiftyOptionExpiries(NOW))
            .containsExactly(LocalDate.parse("2026-11-24"));
        http.verify();
    }

    @Test
    void allEmptyValidBucketsReturnNoListedExpiriesRatherThanSourceFailure() {
        token();
        String empty="{\"status\":\"success\",\"data\":[]}";
        for(String period:new String[]{"current_week","next_week","far_week",
                                       "current_month","next_month","far_month"}) {
            expect(period,empty);
        }
        assertThat(source.currentNiftyOptionExpiries(NOW)).isEmpty();
        http.verify();
    }

    @Test
    void rejectsMalformedContractInsteadOfShowingInferredExpiries() {
        token();
        expect("current_week",rows("2026-11-17").replace("NSE_FO|61002","NSE_EQ|61002"));
        assertThatThrownBy(()->source.currentNiftyOptionExpiries(NOW))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("UPSTOX_FNO_INVALID_EXPIRY_CONTRACTS")
            .hasMessageNotContaining("hidden-test-only");
        http.verify();
    }

    @Test
    void rejectsExpiredAndUnpairedContractsWithoutPromotingToListed() {
        token();
        String[] periods={"current_week","next_week","far_week",
                          "current_month","next_month","far_month"};
        // Actual provider data is required. A CE-only expiry is not listed.
        for(String p:periods){
            String onlyCe="""
                {"status":"success","data":[
                  {"underlying_key":"NSE_INDEX|Nifty 50","segment":"NSE_FO",
                   "exchange":"NSE","instrument_key":"NSE_FO|61001",
                   "expiry":"2026-11-17","instrument_type":"CE",
                   "strike_price":26000,"tick_size":5,"lot_size":65}
                ]}
                """;
            expect(p,onlyCe);
        }
        assertThat(source.currentNiftyOptionExpiries(NOW)).isEmpty();
        http.verify();
    }

    @Test
    void missingProviderPermissionOrHttpFailureNeverInventsExpiries() {
        UpstoxAnalyticsSource disabled=new UpstoxAnalyticsSource(false,ssm,builder.build());
        assertThatThrownBy(()->disabled.currentNiftyOptionExpiries(NOW))
            .hasMessage("UPSTOX_ANALYTICS_DISABLED");
        verifyNoInteractions(ssm);
        token();
        http.expect(requestTo(org.hamcrest.Matchers.containsString("expiry_date=current_week")))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).body("hidden-test-only"));
        assertThatThrownBy(()->source.currentNiftyOptionExpiries(NOW))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("hidden-test-only");
        http.verify();
    }
}
