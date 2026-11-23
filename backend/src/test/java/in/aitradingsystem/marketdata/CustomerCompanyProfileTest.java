package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import in.aitradingsystem.user.CustomerIdentityStore;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class CustomerCompanyProfileTest {
    private static final CustomerMarketUniverseController.CompanyIdentity EQUITY =
        new CustomerMarketUniverseController.CompanyIdentity(
            101L, "APLAPOLLO-EQ", "APL Apollo Tubes Limited", "NSE", "EQ");

    @Test
    void sourcedMembershipShowsIndustryAndIndexClassificationWithoutInventingRatios() {
        var profile = CustomerMarketUniverseController.profileFromProofs(EQUITY, List.of(
            new CustomerMarketUniverseController.IndustryProof(
                "NIFTY100", "Capital Goods", "NSE_NIFTY100_CSV", LocalDate.of(2026, 11, 20)),
            new CustomerMarketUniverseController.IndustryProof(
                "NIFTY500", "Capital Goods", "NSE_NIFTY500_CSV", LocalDate.of(2026, 11, 20))));
        assertThat(profile.contractVersion()).isEqualTo("customer.market.company-profile.v1");
        assertThat(profile.instrumentId()).isEqualTo(101L);
        assertThat(profile.industry()).isEqualTo("Capital Goods");
        assertThat(profile.sizeSegment()).isEqualTo("LARGE");
        assertThat(profile.indexMemberships()).hasSize(2);
    }

    @Test
    void missingOrConflictingProofsDoNotInventIndustryOrSize() {
        var noProof = CustomerMarketUniverseController.profileFromProofs(EQUITY, List.of());
        assertThat(noProof.industry()).isNull();
        assertThat(noProof.sizeSegment()).isNull();
        assertThat(noProof.indexMemberships()).isEmpty();

        var conflicting = CustomerMarketUniverseController.profileFromProofs(EQUITY, List.of(
            new CustomerMarketUniverseController.IndustryProof(
                "NIFTY500", "Healthcare", "NSE_NIFTY500_CSV", LocalDate.of(2026, 11, 20)),
            new CustomerMarketUniverseController.IndustryProof(
                "MIDCAP150", "Capital Goods", "NSE_MIDCAP150_CSV", LocalDate.of(2026, 11, 20))));
        assertThat(conflicting.industry()).isNull();
        assertThat(conflicting.sizeSegment()).isEqualTo("MID");
    }

    @Test
    void rejectsFutureDatedOrUnattributedProof() {
        var rejected = CustomerMarketUniverseController.profileFromProofs(EQUITY, List.of(
            new CustomerMarketUniverseController.IndustryProof(
                "NIFTY100", "Industrials", "NSE_NIFTY100_CSV", LocalDate.of(2099, 1, 1)),
            new CustomerMarketUniverseController.IndustryProof(
                "NIFTY500", "Industrials", "", LocalDate.of(2026, 11, 20))));
        assertThat(rejected.industry()).isNull();
        assertThat(rejected.sizeSegment()).isNull();
        assertThat(rejected.indexMemberships()).isEmpty();
    }

    @Test
    void unauthenticatedClientCannotLookUpCompanyIdentity() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var controller = new CustomerMarketUniverseController(jdbc, mock(CustomerIdentityStore.class));
        assertThatThrownBy(() -> controller.companyProfile(null, 101L))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }
}
