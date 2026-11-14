package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.provider.upstox.UpstoxSourceRightsGate.Grant;
import in.aitradingsystem.provider.upstox.UpstoxSourceRightsGate.GrantStatus;
import in.aitradingsystem.provider.upstox.UpstoxSourceRightsGate.Use;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UpstoxSourceRightsGateTest {
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");

    private static Grant verified(Use use) {
        return new Grant("UPSTOX_ANALYTICS", use, GrantStatus.VERIFIED,
                "rights-review-123", NOW.plusSeconds(3600));
    }

    @Test
    void allowsOnlyExplicitVerifiedUnexpiredUse() {
        assertThatCode(() -> UpstoxSourceRightsGate.requireAllowed(
                "UPSTOX_ANALYTICS", Use.EPHEMERAL_READ,
                verified(Use.EPHEMERAL_READ), NOW)).doesNotThrowAnyException();
        for (Use use : new Use[] {Use.RETAIN, Use.TRAIN, Use.PUBLISH}) {
            assertThatThrownBy(() -> UpstoxSourceRightsGate.requireAllowed(
                    "UPSTOX_ANALYTICS", use, verified(Use.EPHEMERAL_READ), NOW))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsMissingRevokedWrongSourceAndExpiredEvidence() {
        Grant[] invalid = {
                null,
                new Grant("UPSTOX_ANALYTICS", Use.TRAIN, GrantStatus.UNVERIFIED,
                        "review", NOW.plusSeconds(60)),
                new Grant("UPSTOX_ANALYTICS", Use.TRAIN, GrantStatus.REVOKED,
                        "review", NOW.plusSeconds(60)),
                new Grant("ANGEL_ONE", Use.TRAIN, GrantStatus.VERIFIED,
                        "review", NOW.plusSeconds(60)),
                new Grant("UPSTOX_ANALYTICS", Use.TRAIN, GrantStatus.VERIFIED,
                        "", NOW.plusSeconds(60)),
                new Grant("UPSTOX_ANALYTICS", Use.TRAIN, GrantStatus.VERIFIED,
                        "review", NOW)
        };
        for (Grant grant : invalid) {
            assertThatThrownBy(() -> UpstoxSourceRightsGate.requireAllowed(
                    "UPSTOX_ANALYTICS", Use.TRAIN, grant, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
