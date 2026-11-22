package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** V125 and read-only SQL contract; MOCK database, not real historical data. */
class Fno170VerifiedIntradayPairStoreTest {
    private static final String STOCK="NSE_EQ|INE002A01018";
    private static final String FUT="NSE_FO|DEC_170";
    private static final LocalDate EXP=LocalDate.of(2026,12,24);
    private static final Instant ASOF=Instant.parse("2026-11-20T05:00:00Z");

    @Test void exactSourceQueryJoinsFourVerifiedUseRightsAndBothNseCalendars(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var store=new Fno170VerifiedIntradayPairStore(jdbc);
        assertTrue(store.readExactPair(STOCK,FUT,EXP,250,ASOF).isEmpty());
        var query=mockingDetails(jdbc).getInvocations().stream()
            .filter(c->"query".equals(c.getMethod().getName())).findFirst().orElseThrow();
        Object[] args=query.getArguments();
        String sql=(String)args[0];
        assertEquals(34,sql.chars().filter(c->c=='?').count());
        assertEquals(36,args.length);
        assertEquals(STOCK,args[2]);
        assertEquals(FUT,args[3]);
        assertEquals(java.sql.Date.valueOf(EXP),args[4]);
        assertEquals(250,args[5]);
        assertEquals(java.sql.Timestamp.from(ASOF.minusSeconds(15)),args[6]);
        assertEquals(java.sql.Timestamp.from(ASOF),args[7]);
        for(String required:java.util.List.of(
                "nse_eq_intraday_bidask","nse_fo_intraday_bidask",
                "nse_fo_contract_listing","nse_eq_futures_basis_costs",
                "spot_rights.receipt_status='VERIFIED'",
                "future_rights.receipt_status='VERIFIED'",
                "listing_rights.receipt_status='VERIFIED'",
                "cost_rights.receipt_status='VERIFIED'",
                "cash_cal.session_status='TRADING'",
                "future_cal.session_status='TRADING'",
                "cash_cal.verified_at<=?","future_cal.verified_at<=?",
                "spot_rights.verified_at<=?","future_rights.verified_at<=?",
                "listing_rights.verified_at<=?","cost_rights.verified_at<=?",
                "s.spot_first_observed_at<=?","s.futures_first_observed_at<=?",
                "s.captured_at<=?","s.retain_until>=?",
                "ORDER BY s.futures_exchange_at DESC LIMIT 1")) {
            assertTrue(sql.contains(required),required);
        }
        assertFalse(sql.toUpperCase().contains("INSERT INTO"));
        assertFalse(sql.contains("fno_verified_expiry_basis_pair_evidence"));
    }

    @Test void exactIdentityAndLotAreValidatedBeforeSourceRead(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var store=new Fno170VerifiedIntradayPairStore(jdbc);
        assertThrows(IllegalArgumentException.class,()->
            store.readExactPair("NSE_INDEX|Nifty",FUT,EXP,250,ASOF));
        assertThrows(IllegalArgumentException.class,()->
            store.readExactPair(STOCK,"NSE_EQ|STOCK",EXP,250,ASOF));
        assertThrows(IllegalArgumentException.class,()->
            store.readExactPair(STOCK,FUT,EXP,0,ASOF));
        assertThrows(IllegalArgumentException.class,()->
            store.readExactPair(STOCK,FUT,LocalDate.of(2026,11,19),250,ASOF));
        verifyNoInteractions(jdbc);
    }

    @Test void newV125SourceIsEmptyAppendOnlyAndNeverSeedsUnverifiedQuotes() throws Exception {
        try(var stream=getClass().getResourceAsStream(
            "/db/migration/V125__fno_verified_intraday_cash_futures_basis_source.sql")) {
            assertNotNull(stream);
            String ddl=new String(stream.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(ddl.contains(
                "CREATE TABLE fno_verified_intraday_cash_futures_basis_source"));
            for(String required:java.util.List.of(
                "spot_exchange_at TIMESTAMPTZ NOT NULL",
                "futures_exchange_at TIMESTAMPTZ NOT NULL",
                "spot_first_observed_at TIMESTAMPTZ NOT NULL",
                "futures_first_observed_at TIMESTAMPTZ NOT NULL",
                "spot_rights_receipt_id UUID NOT NULL",
                "futures_rights_receipt_id UUID NOT NULL",
                "listing_rights_receipt_id UUID NOT NULL",
                "costs_rights_receipt_id UUID NOT NULL",
                "spot_calendar_evidence_id UUID NOT NULL",
                "futures_calendar_evidence_id UUID NOT NULL",
                "BEFORE UPDATE OR DELETE")) {
                assertTrue(ddl.contains(required),required);
            }
            assertFalse(ddl.contains(
                "INSERT INTO fno_verified_intraday_cash_futures_basis_source"));
        }
    }
}
