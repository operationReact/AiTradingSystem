package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Offline original-source fixture tests: never market historical E2E proof. */
class Fno176OriginalObservedIngestorTest {
    private static final Instant NOW=Instant.parse("2026-11-24T08:00:00Z");
    private static final UUID CAL=UUID.randomUUID(), OH=UUID.randomUUID(), OI=UUID.randomUUID();
    private static final Fno176OriginalObservedIngestor.OriginalPackets PACKETS =
        new Fno176OriginalObservedIngestor.OriginalPackets(
            "UPSTOX_ANALYTICS",
            "provider-ohlcv-packet".getBytes(StandardCharsets.UTF_8),
            "provider-final-oi".getBytes(StandardCharsets.UTF_8),
            "provider-contract-listing".getBytes(StandardCharsets.UTF_8));

    private final NamedParameterJdbcTemplate db=mock(NamedParameterJdbcTemplate.class);
    private final GoldenSourceRightsRepository rights=mock(GoldenSourceRightsRepository.class);
    private final Fno176OriginalObservedIngestor.IndependentlyVerifiedSource verifier=
        mock(Fno176OriginalObservedIngestor.IndependentlyVerifiedSource.class);

    private Fno176OriginalObservedIngestor gate() {
        return new Fno176OriginalObservedIngestor(db,rights,verifier,
            Clock.fixed(NOW,ZoneOffset.UTC));
    }

    private Fno176OriginalObservedIngestor.VerifiedDay day() {
        return new Fno176OriginalObservedIngestor.VerifiedDay(
            "UPSTOX_ANALYTICS", "NSE_FO|REAL_FUTURE_CONTRACT",
            "NSE_INDEX|NIFTY",LocalDate.parse("2026-11-01"),
            LocalDate.parse("2026-11-26"),75,LocalDate.parse("2026-11-23"),
            new BigDecimal("25500.00"),new BigDecimal("25600.00"),
            new BigDecimal("25400.00"),new BigDecimal("25570.00"),
            100000,10000,CAL,OH,OI,LocalDate.parse("2026-12-20"));
    }

    private void grantAll() {
        when(rights.isPermitted(eq("UPSTOX_ANALYTICS"),anyString(),any(),
                eq(NOW))).thenReturn(true);
    }

    @Test void noRightsMeansNoSourceVerifierNoSQLNoImplicitGrant() {
        var result=gate().ingest(PACKETS);
        assertEquals("ORIGINAL_RESEARCH_USE_NOT_AUTHORIZED",result.state());
        assertFalse(result.originalSessionStored());
        assertFalse(result.independentHistoricalE2ECertified());
        verifyNoInteractions(verifier,db);
    }

    @Test void verifiedBytesAndGenuineCalendarRowsCanBeInsertedOnlyWithDbObservedAt() throws Exception {
        grantAll();
        when(verifier.verify(PACKETS)).thenReturn(Optional.of(day()));
        when(db.update(anyString(),any(MapSqlParameterSource.class))).thenReturn(1);
        var result=gate().ingest(PACKETS);
        assertEquals("ORIGINAL_OBSERVED_SOURCE_STORED_NOT_E2E",result.state());
        assertTrue(result.originalSessionStored());
        assertFalse(result.independentHistoricalE2ECertified());
        assertFalse(result.paperOrdersPlaced());
        assertFalse(result.liveOrdersPlaced());
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var bound=org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(db).update(sql.capture(),bound.capture());
        String query=sql.getValue();
        for(String keyword:java.util.List.of(
                "INSERT INTO fno_verified_completed_ohlcv_oi_session",
                "clock_timestamp() AS accepted_at",
                "observed.accepted_at, observed.accepted_at",
                "p.source_kind = 'EXTERNAL'",
                "ohlcv.permitted_use = 'MODEL_INFERENCE'",
                "oi.permitted_use = 'MODEL_INFERENCE'",
                "cal.session_status = 'TRADING'",
                "cal.captured_at <= observed.accepted_at",
                "cal.session_close_at <= observed.accepted_at",
                "raw.permitted_use = 'RAW_RETENTION'",
                "usage.permitted_use = 'MODEL_INFERENCE'",
                "revoked.receipt_status = 'REVOKED'",
                "r.verified_at <= observed.accepted_at",
                "r.captured_at <= observed.accepted_at"))
            assertTrue(query.contains(keyword),keyword);
        assertFalse(query.contains("market_candle"));
        assertFalse(query.contains("fno_closing_snapshot"));
        assertFalse(query.contains("ON CONFLICT"));
        assertFalse(query.toUpperCase().contains("UPDATE "));
        assertFalse(query.toUpperCase().contains("DELETE FROM FNO_"));
        var map=bound.getValue();
        assertEquals("UPSTOX_ANALYTICS",map.getValue("provider"));
        assertEquals(CAL,map.getValue("calendar"));
        assertEquals(OH,map.getValue("ohlcvRights"));
        assertEquals(OI,map.getValue("oiRights"));
        assertEquals(hash(PACKETS.ohlcvPacket()),map.getValue("ohlcvHash"));
        assertEquals(hash(PACKETS.finalOiPacket()),map.getValue("oiHash"));
        assertEquals(hash(PACKETS.contractMasterPacket()),map.getValue("listingHash"));
        assertFalse(map.hasValue("ohlcvFirstKnownAt"));
        assertFalse(map.hasValue("oiFirstKnownAt"));
        assertFalse(map.hasValue("capturedAt"));
    }

    @Test void untrustedSourceCannotInventOriginalPublicationOrHistoricRows() {
        grantAll();
        when(verifier.verify(PACKETS)).thenReturn(Optional.empty());
        assertEquals("INDEPENDENT_SOURCE_VERIFICATION_FAILED",gate().ingest(PACKETS).state());
        when(verifier.verify(PACKETS)).thenReturn(Optional.of(new Fno176OriginalObservedIngestor.VerifiedDay(
            day().providerCode(),day().instrumentKey(),day().underlyingKey(),
            day().listedFrom(),day().expiry(),day().lotSize(),
            LocalDate.parse("2026-11-24"),day().open(),day().high(),
            day().low(),day().close(),day().volumeUnits(),day().finalOiUnits(),
            CAL,OH,OI,day().retainUntil())));
        assertEquals("INDEPENDENT_SOURCE_VERIFICATION_FAILED",gate().ingest(PACKETS).state());
        verifyNoInteractions(db);
    }

    @Test void mismatchedProviderBadVolumeAndRightsReceiptReuseFail() {
        grantAll();
        var d=day();
        for(var wrong:java.util.List.of(
            new Fno176OriginalObservedIngestor.VerifiedDay(
                "ANGEL_ONE_SMARTAPI",d.instrumentKey(),d.underlyingKey(),
                d.listedFrom(),d.expiry(),d.lotSize(),d.sessionDate(),d.open(),
                d.high(),d.low(),d.close(),d.volumeUnits(),d.finalOiUnits(),
                CAL,OH,OI,d.retainUntil()),
            new Fno176OriginalObservedIngestor.VerifiedDay(
                d.providerCode(),d.instrumentKey(),d.underlyingKey(),d.listedFrom(),
                d.expiry(),d.lotSize(),d.sessionDate(),d.open(),d.high(),
                d.low(),d.close(),d.volumeUnits(),0,CAL,OH,OI,d.retainUntil()),
            new Fno176OriginalObservedIngestor.VerifiedDay(
                d.providerCode(),d.instrumentKey(),d.underlyingKey(),d.listedFrom(),
                d.expiry(),d.lotSize(),d.sessionDate(),d.open(),d.high(),
                d.low(),d.close(),d.volumeUnits(),d.finalOiUnits(),
                CAL,OH,OH,d.retainUntil()))) {
            when(verifier.verify(PACKETS)).thenReturn(Optional.of(wrong));
            assertFalse(gate().ingest(PACKETS).originalSessionStored());
        }
        verifyNoInteractions(db);
    }

    @Test void insufficientRightsDuringEvenOneDomainNeverTouchesSql() {
        when(rights.isPermitted(eq("UPSTOX_ANALYTICS"),anyString(),any(),
            eq(NOW))).thenAnswer(call ->
                !"nse_fo_final_oi".equals(call.getArgument(1))
                 || call.getArgument(2)!=GoldenSourceRightsRepository.Use.MODEL_INFERENCE);
        assertEquals("ORIGINAL_RESEARCH_USE_NOT_AUTHORIZED",gate().ingest(PACKETS).state());
        verifyNoInteractions(verifier,db);
    }

    @Test void duplicateSourceRevisionOrSqlErrorDoesNotChangePriorEvidence() {
        grantAll();
        when(verifier.verify(PACKETS)).thenReturn(Optional.of(day()));
        when(db.update(anyString(),any(MapSqlParameterSource.class)))
            .thenThrow(new DuplicateKeyException("private user record"));
        var out=gate().ingest(PACKETS);
        assertEquals("ORIGINAL_SOURCE_INSERT_REJECTED",out.state());
        assertFalse(out.originalSessionStored());
        assertFalse(out.toString().contains("private user"));
        verify(db).update(anyString(),any(MapSqlParameterSource.class));
    }

    @Test void rawProviderPacketsCannotBeMutatedAfterCapture() {
        byte[] payload="original".getBytes(StandardCharsets.UTF_8);
        var packets=new Fno176OriginalObservedIngestor.OriginalPackets(
            "UPSTOX_ANALYTICS",payload,new byte[]{1},new byte[]{2});
        payload[0]=(byte)'X';
        assertEquals("original",new String(packets.ohlcvPacket(),StandardCharsets.UTF_8));
        byte[] retrieved=packets.ohlcvPacket();
        retrieved[0]=(byte)'Y';
        assertEquals("original",new String(packets.ohlcvPacket(),StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,() ->
            new Fno176OriginalObservedIngestor.OriginalPackets(
                "UPSTOX_ANALYTICS",new byte[0],new byte[]{1},new byte[]{2}));
        // The official instrument-master gzip can exceed the 1 MiB candle cap.
        byte[] publishedMaster=new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(publishedMaster,(byte)'X');
        var masterPackets=new Fno176OriginalObservedIngestor.OriginalPackets(
            "UPSTOX_ANALYTICS",new byte[]{1},new byte[]{2},publishedMaster);
        assertEquals(publishedMaster.length,masterPackets.contractMasterPacket().length);
        assertThrows(IllegalArgumentException.class,() ->
            new Fno176OriginalObservedIngestor.OriginalPackets(
                "UPSTOX_ANALYTICS",new byte[1_048_577],new byte[]{2},
                new byte[]{3}));

    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
