package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

/** Uses real PostgreSQL JSONB, active-catalogue selection and source filters. */
class CustomerFullQuoteCoverageIT extends AbstractPostgresIT {
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    void fullUniverseCountsVerifiedStoredQuotesButNotMissingOrUnclassifiedBse() {
        UUID version = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO instrument_import_run (id, status, source_url, catalogue_version)
            VALUES (?, 'SUCCEEDED', 'test-full-quote-coverage', ?)
            """, version, version);
        jdbc.update("""
            UPDATE instrument_catalogue_state SET active_version = ?, activated_at = now()
             WHERE singleton_id = 1
            """, version);
        long nse = stock(version, "NSE_CM", "NSE", "ALPHA-EQ", "EQ");
        long bse = stock(version, "BSE_CM", "BSE", "ALPHA", "EQ");
        long unclassified = stock(version, "BSE_CM", "BSE", "UNKNOWN", null);
        long unsupported = stock(version, "BSE_CM", "BSE", "BETA", "EQ");
        stored(nse, "NSE", "ALPHA-EQ", "ANGEL_ONE_SMARTAPI_FULL_APP", 100);
        stored(bse, "BSE", "ALPHA", "ANGEL_ONE_SMARTAPI_FULL_APP", null);
        stored(unsupported, "BSE", "OTHER", "ANGEL_ONE_SMARTAPI_FULL_APP", 80);
        stored(unclassified, "BSE", "UNKNOWN", "ANGEL_ONE_SMARTAPI_FULL_APP", 50);

        CustomerIdentityStore identities = mock(CustomerIdentityStore.class);
        String issuer = "https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_test";
        when(identities.find("cognito", issuer, "subject-test")).thenReturn(
            Optional.of(new CustomerIdentityStore.CustomerAccount(
                UUID.randomUUID(), "ACTIVE", "CUSTOMER", "Test", "test@example.test")));
        var jwt = Jwt.withTokenValue("synthetic-test")
            .header("alg", "RS256").issuer(issuer).subject("subject-test")
            .issuedAt(Instant.parse("2026-11-12T00:00:00Z"))
            .expiresAt(Instant.parse("2026-11-14T00:00:00Z")).build();
        var principal = new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        var response = new CustomerMarketUniverseController(jdbc, identities)
            .fullQuoteCoverage(principal).getBody();

        assertThat(response).isNotNull();
        assertThat(response.totalActiveListings()).isEqualTo(3);
        assertThat(response.storedFullQuotes()).isEqualTo(2);
        assertThat(response.withProviderPreviousClose()).isEqualTo(1);
        assertThat(response.activeBseCmRecords()).isEqualTo(3);
        assertThat(response.exchanges()).extracting("exchange")
            .containsExactly("NSE", "BSE");
        assertThat(response.exchanges().get(0).storedFullQuotes()).isEqualTo(1);
        assertThat(response.exchanges().get(1).storedFullQuotes()).isEqualTo(1);
        assertThat(response.exchanges().get(1).withProviderPreviousClose()).isZero();
    }

    private long stock(UUID version, String segment, String exchange,
                       String symbol, String type) {
        Long id = jdbc.queryForObject("""
            INSERT INTO instrument_master (
                exchange_segment, exchange, symbol_token, trading_symbol,
                instrument_name, instrument_type, lot_size, tick_size, catalogue_version
            ) VALUES (?, ?, ?, ?, ?, ?, 1, 0.05, ?) RETURNING instrument_id
            """, Long.class, segment, exchange, UUID.randomUUID().toString().replace("-", "").substring(0, 12),
            symbol, "Test " + symbol, type, version);
        return id.longValue();
    }

    private void stored(long instrumentId, String exchange, String symbol,
                        String source, Integer previousClose) {
        String previous = previousClose == null ? "null" : previousClose.toString();
        String json = """
            {"instrumentId":%d,"source":"%s","exchange":"%s","tradingSymbol":"%s",
             "ltp":110,"previousClose":%s,"exchangeAt":"2026-11-12T09:59:00Z"}
            """.formatted(instrumentId, source, exchange, symbol, previous);
        jdbc.update("""
            INSERT INTO market_detail_snapshot (instrument_id, fetched_at, payload)
            VALUES (?, now(), CAST(? AS jsonb))
            """, instrumentId, json);
    }
}
