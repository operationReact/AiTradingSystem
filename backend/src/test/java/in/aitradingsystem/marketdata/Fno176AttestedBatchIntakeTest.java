package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Synthetic cryptographic fixtures only; no genuine market certification. */
class Fno176AttestedBatchIntakeTest {
    private static final Instant NOW=Instant.parse("2026-11-24T08:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final String PROVIDER="UPSTOX_ANALYTICS";
    private final NamedParameterJdbcTemplate db=mock(NamedParameterJdbcTemplate.class);
    private final GoldenSourceRightsRepository rights=mock(GoldenSourceRightsRepository.class);
    private final Fno176OriginalObservedIngestor.OriginalPackets packets=
        new Fno176OriginalObservedIngestor.OriginalPackets(PROVIDER,
            "ohlcv".getBytes(StandardCharsets.UTF_8),"oi".getBytes(StandardCharsets.UTF_8),
            "listing".getBytes(StandardCharsets.UTF_8));
    private final Fno176OriginalObservedIngestor.VerifiedDay day=
        new Fno176OriginalObservedIngestor.VerifiedDay(PROVIDER,"NSE_FO|TEST",
            "NSE_INDEX|Nifty 50",LocalDate.parse("2026-11-01"),LocalDate.parse("2026-12-29"),
            75,LocalDate.parse("2026-11-23"),new BigDecimal("25000"),new BigDecimal("25300"),
            new BigDecimal("24900"),new BigDecimal("25100"),100000,200000,
            UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),LocalDate.parse("2026-12-20"));

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Fno176AttestedSourceVerifier.Receipt receipt(KeyPair pair,Instant at) throws Exception {
        var unsigned=new Fno176AttestedSourceVerifier.Receipt(day,hash(packets.ohlcvPacket()),
            hash(packets.finalOiPacket()),hash(packets.contractMasterPacket()),at,new byte[0]);
        var signer=Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(Fno176AttestedSourceVerifier.signingBytes(unsigned));
        return new Fno176AttestedSourceVerifier.Receipt(day,unsigned.ohlcvSha256(),
            unsigned.oiSha256(),unsigned.listingSha256(),at,signer.sign());
    }

    @Test void onlyTrustedSignatureAndExactPacketsReachExistingDbGate() throws Exception {
        var trusted=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var other=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var valid=receipt(trusted,NOW);
        assertEquals(day,new Fno176AttestedSourceVerifier(trusted.getPublic(),CLOCK,valid)
            .verify(packets).orElseThrow());
        assertTrue(new Fno176AttestedSourceVerifier(other.getPublic(),CLOCK,valid)
            .verify(packets).isEmpty());
        assertTrue(new Fno176AttestedSourceVerifier(trusted.getPublic(),CLOCK,
            receipt(trusted,NOW.plusSeconds(1))).verify(packets).isEmpty());
        assertTrue(new Fno176AttestedSourceVerifier(trusted.getPublic(),CLOCK,
            receipt(trusted,NOW.minusSeconds(8*86400L))).verify(packets).isEmpty());
        var changed=new Fno176OriginalObservedIngestor.OriginalPackets(PROVIDER,
            "altered".getBytes(StandardCharsets.UTF_8),packets.finalOiPacket(),
            packets.contractMasterPacket());
        assertTrue(new Fno176AttestedSourceVerifier(trusted.getPublic(),CLOCK,valid)
            .verify(changed).isEmpty());
        var intake=new Fno176AttestedBatchIntake(db,rights,trusted.getPublic(),CLOCK);
        var e=new Fno176AttestedBatchIntake.Entry(packets,valid);
        assertThrows(IllegalArgumentException.class,()->intake.ingest(List.of(e,e)));
        verifyNoInteractions(db,rights);
        assertEquals(0,intake.ingest(List.of(e)).stored());
        verifyNoInteractions(db);
        when(rights.isPermitted(eq(PROVIDER),anyString(),any(),eq(NOW))).thenReturn(true);
        assertEquals(0,intake.ingest(List.of(new Fno176AttestedBatchIntake.Entry(changed,valid))).stored());
        verifyNoInteractions(db);
        when(db.update(anyString(),any(MapSqlParameterSource.class))).thenReturn(1);
        var stored=intake.ingest(List.of(e));
        assertEquals(1,stored.stored());
        assertEquals(0,stored.rejected());
        assertFalse(stored.independentlyHistoricalE2ECertified());
        assertFalse(stored.ordersPlaced());
        verify(db).update(anyString(),any(MapSqlParameterSource.class));
    }
}
