package in.aitradingsystem.news.policy;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.news.policy.NewsSourcePolicy.Decision;
import in.aitradingsystem.news.policy.NewsSourcePolicy.LicenseState;
import in.aitradingsystem.news.policy.NewsSourcePolicy.Row;
import in.aitradingsystem.news.policy.NewsSourcePolicy.Use;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Pure default-deny decision rules. No database. */
class NewsSourcePolicyEvaluationTest {
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    private static Row row(LicenseState state, List<Use> uses, Optional<Instant> from, Optional<Instant> to) {
        return new Row("src", "Publisher", state, uses, "reviewer-1", "terms", from, to, 30);
    }

    private static final Optional<Instant> EFFECTIVE = Optional.of(NOW.minusSeconds(86_400));

    @Test
    void unknownSourceIsDenied() {
        assertThat(NewsSourcePolicy.evaluate(Optional.empty(), Use.INGEST, NOW))
            .isEqualTo(Decision.DENY_UNKNOWN_SOURCE);
    }

    @Test
    void nonApprovedStatesAreEachDeniedWithAReason() {
        List<Use> all = List.of(Use.values());
        assertThat(NewsSourcePolicy.evaluate(Optional.of(row(LicenseState.REVIEW_PENDING, all, EFFECTIVE, Optional.empty())),
            Use.INGEST, NOW)).isEqualTo(Decision.DENY_REVIEW_PENDING);
        assertThat(NewsSourcePolicy.evaluate(Optional.of(row(LicenseState.REJECTED, all, EFFECTIVE, Optional.empty())),
            Use.INGEST, NOW)).isEqualTo(Decision.DENY_REJECTED);
        assertThat(NewsSourcePolicy.evaluate(Optional.of(row(LicenseState.REVOKED, all, EFFECTIVE, Optional.empty())),
            Use.INGEST, NOW)).isEqualTo(Decision.DENY_REVOKED);
    }

    @Test
    void approvedSourceNeedsAnEffectiveStartAndAListedUse() {
        assertThat(NewsSourcePolicy.evaluate(Optional.of(row(LicenseState.APPROVED, List.of(Use.INGEST),
            Optional.empty(), Optional.empty())), Use.INGEST, NOW)).isEqualTo(Decision.DENY_NOT_EFFECTIVE);
        assertThat(NewsSourcePolicy.evaluate(Optional.of(row(LicenseState.APPROVED, List.of(Use.INGEST),
            Optional.of(NOW.plusSeconds(60)), Optional.empty())), Use.INGEST, NOW)).isEqualTo(Decision.DENY_NOT_EFFECTIVE);
        assertThat(NewsSourcePolicy.evaluate(Optional.of(row(LicenseState.APPROVED, List.of(Use.INGEST),
            EFFECTIVE, Optional.empty())), Use.NLP_TRANSFER, NOW)).isEqualTo(Decision.DENY_USE_NOT_PERMITTED);
        assertThat(NewsSourcePolicy.evaluate(Optional.of(row(LicenseState.APPROVED, List.of(Use.INGEST),
            EFFECTIVE, Optional.empty())), Use.INGEST, NOW)).isEqualTo(Decision.PERMIT);
    }

    @Test
    void expiryIsExclusiveAtTheEndInstant() {
        Optional<Row> expiring = Optional.of(row(LicenseState.APPROVED, List.of(Use.INGEST), EFFECTIVE,
            Optional.of(NOW)));
        assertThat(NewsSourcePolicy.evaluate(expiring, Use.INGEST, NOW.minusSeconds(1))).isEqualTo(Decision.PERMIT);
        assertThat(NewsSourcePolicy.evaluate(expiring, Use.INGEST, NOW)).isEqualTo(Decision.DENY_EXPIRED);
    }
}
