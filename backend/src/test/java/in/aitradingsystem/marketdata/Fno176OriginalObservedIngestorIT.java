package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runner-local PostgreSQL 16+ fixtures ONLY; synthetic sources/rights/calendar.
 * Demonstrates real V102 append-only, rights joined, first-observed-at database
 * semantics, but NEVER proves a licensed market feed or historical E2E.
 */
@Transactional
class Fno176OriginalObservedIngestorIT extends AbstractPostgresIT {
    private static final String PROVIDER="UPSTOX_ANALYTICS";
    private static final String HASH="f".repeat(64);
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");

    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired GoldenSourceRightsRepository rights;

    private static OffsetDateTime at(Instant i){
        return OffsetDateTime.ofInstant(i,ZoneOffset.UTC);
    }

    private UUID provider(){
        jdbc.update("""
            INSERT INTO golden_data_provider
                (provider_id,provider_code,legal_name,source_kind)
            VALUES (?,'UPSTOX_ANALYTICS','Synthetic provider fixture, not a licence','EXTERNAL')
            ON CONFLICT(provider_code) DO NOTHING
            """,UUID.randomUUID());
        return jdbc.queryForObject(
            "SELECT provider_id FROM golden_data_provider WHERE provider_code = ?",
            UUID.class,PROVIDER);
    }

    private UUID grant(UUID provider,String domain,String use){
        UUID id=UUID.randomUUID();
        Instant now=Instant.now();
        jdbc.update("""
            INSERT INTO golden_source_rights_receipt(
              receipt_id,provider_id,source_domain,permitted_use,receipt_status,
              terms_digest,valid_from,verified_at,verified_by,
              source_reference,captured_at)
            VALUES (?,?,?,?,'VERIFIED',?,?,?,'FIXTURE_ONLY_VERIFIER',
                    'synthetic_only_not_an_external_permission',clock_timestamp())
            """,id,provider,domain,use,HASH,
            at(now.minusSeconds(86400)),at(now.minusSeconds(1200)));
        return id;
    }

    private UUID calendar(UUID provider, LocalDate date){
        UUID id=UUID.randomUUID();
        Instant close=date.atTime(15,40).atZone(IST).toInstant();
        Instant first=close.plusSeconds(60);
        Instant recorded=close.plusSeconds(120);
        jdbc.update("""
            INSERT INTO golden_historical_exchange_session_evidence(
              evidence_id,provider_id,source_domain,exchange,session_date,
              source_revision,session_status,session_close_at,
              source_published_at,first_available_at,calendar_evidence_digest,
              verified_at,verified_by,source_reference,captured_at)
            VALUES (?,?,'nse_fo_trading_calendar','NSE',?,1,'TRADING',?,?,?,
                    ?,?,'FIXTURE_ONLY_VERIFIER',
                    'synthetic_calendar_not_an_exchange_record',clock_timestamp())
            """,id,provider,java.sql.Date.valueOf(date),
            at(close),at(first),at(recorded),HASH,
            at(Instant.now().minusSeconds(600)));
        return id;
    }

    private Fno176OriginalObservedIngestor.OriginalPackets packets(){
        return new Fno176OriginalObservedIngestor.OriginalPackets(
            PROVIDER,"sample-ohlcv-only".getBytes(StandardCharsets.UTF_8),
            "sample-final-oi-only".getBytes(StandardCharsets.UTF_8),
            "sample-verified-contract-metadata-only".getBytes(StandardCharsets.UTF_8));
    }

    private Fno176OriginalObservedIngestor.VerifiedDay session(
            String key, UUID cal, UUID oh, UUID oi) {
        var day=LocalDate.now(IST).minusDays(1);
        return new Fno176OriginalObservedIngestor.VerifiedDay(
            PROVIDER,key,"NSE_INDEX|NIFTY",day.minusDays(20),
            day.plusDays(26),75,day,new BigDecimal("25000.000000"),
            new BigDecimal("25300.000000"),new BigDecimal("24900.000000"),
            new BigDecimal("25175.000000"),123456,60000,
            cal,oh,oi,day.plusDays(15));
    }

    @Test void emptyOriginalRightsRejectBeforeAnyV102Write(){
        provider();
        String key="NSE_FO|UNLICENSED_"+UUID.randomUUID().toString().replace("-","");
        var result=new Fno176OriginalObservedIngestor(
            named,rights,p -> { throw new AssertionError("must not parse source"); },
            Clock.systemUTC()).ingest(packets());
        assertEquals("ORIGINAL_RESEARCH_USE_NOT_AUTHORIZED",result.state());
        assertFalse(result.originalSessionStored());
        assertEquals(0L,jdbc.queryForObject(
            "SELECT COUNT(*) FROM fno_verified_completed_ohlcv_oi_session WHERE instrument_key=?",
            Long.class,key));
    }

    @Test void sourceObservedNowIsNotRetroactivelyBackdatedAndReadReplayIsRightsJoined(){
        UUID provider=provider();
        UUID oh=grant(provider,"nse_fo_completed_ohlcv","MODEL_INFERENCE");
        grant(provider,"nse_fo_completed_ohlcv","RAW_RETENTION");
        UUID oi=grant(provider,"nse_fo_final_oi","MODEL_INFERENCE");
        grant(provider,"nse_fo_final_oi","RAW_RETENTION");
        grant(provider,"nse_fo_trading_calendar","MODEL_INFERENCE");
        grant(provider,"nse_fo_trading_calendar","RAW_RETENTION");
        var day=LocalDate.now(IST).minusDays(1);
        UUID cal=calendar(provider,day);
        String key="NSE_FO|TEST_"+UUID.randomUUID().toString().replace("-","");
        var verified=session(key,cal,oh,oi);
        var firstCaptureWindow=Instant.now().minusSeconds(2);
        var importer=new Fno176OriginalObservedIngestor(named,rights,
            p -> Optional.of(verified),Clock.systemUTC());
        var stored=importer.ingest(packets());
        assertEquals("ORIGINAL_OBSERVED_SOURCE_STORED_NOT_E2E",stored.state());
        assertTrue(stored.originalSessionStored());
        assertFalse(stored.independentHistoricalE2ECertified());

        var recorded=jdbc.queryForMap("""
            SELECT ohlcv_first_available_at,oi_first_available_at,
                   captured_at,ohlcv_source_digest,oi_source_digest,
                   listing_source_digest
            FROM fno_verified_completed_ohlcv_oi_session
            WHERE provider_code=? AND instrument_key=? AND session_date=?
            """,PROVIDER,key,java.sql.Date.valueOf(day));
        Instant originalCaptured=((java.sql.Timestamp)recorded.get("captured_at")).toInstant();
        Instant ohlcv=((java.sql.Timestamp)recorded.get("ohlcv_first_available_at")).toInstant();
        Instant oiCaptured=((java.sql.Timestamp)recorded.get("oi_first_available_at")).toInstant();
        assertFalse(originalCaptured.isBefore(firstCaptureWindow));
        assertEquals(originalCaptured,ohlcv);
        assertEquals(originalCaptured,oiCaptured);
        assertTrue(originalCaptured.isAfter(day.atStartOfDay(IST).toInstant()));

        var proof=new FnoVerifiedCompletedOhlcvOiStore(jdbc).readExactReplayContract(
            key,day,day,Instant.now().plusSeconds(60));
        assertEquals(1,proof.size());
        assertEquals(day,proof.getFirst().bar().sessionDate());
        assertFalse(stored.paperOrdersPlaced());
        assertFalse(stored.liveOrdersPlaced());
    }
}
