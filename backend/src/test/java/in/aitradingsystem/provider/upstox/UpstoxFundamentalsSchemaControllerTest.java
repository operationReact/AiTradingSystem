package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class UpstoxFundamentalsSchemaControllerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-18T02:00:00Z"), ZoneOffset.UTC);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private JwtAuthenticationToken auth(String subject) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("fixture")
                .header("alg", "none").claim("sub", subject).build());
    }

    @Test void unauthorizedCannotReadCatalog() {
        var c = new UpstoxFundamentalsSchemaController(jdbc, "owner", clock);
        assertThatThrownBy(() -> c.inspect(auth("other"))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void visibleSchemaNeverBecomesPersistedDataCount() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<UpstoxFundamentalsSchemaController.Candidate>>any()))
                .thenReturn(List.of(new UpstoxFundamentalsSchemaController.Candidate("fundamental_fixture", 4)));
        var response = new UpstoxFundamentalsSchemaController(jdbc, "owner", clock).inspect(auth("owner"));
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody().status()).isEqualTo("SCHEMA_REVIEW_REQUIRED");
        assertThat(response.getBody().rightsState()).isEqualTo("UNKNOWN");
        assertThat(response.getBody().fundamentalsEntities()).isNull();
        assertThat(response.getBody().fundamentalsRecords()).isNull();
        assertThat(response.getBody().corporateEvents()).isNull();
        verify(jdbc).query(contains("information_schema.columns"),
                org.mockito.ArgumentMatchers.<RowMapper<UpstoxFundamentalsSchemaController.Candidate>>any());
    }
}
