package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Private runtime fixtures only, never genuine market evidence. */
class Fno176PrivateIntakeRunnerTest {
    @TempDir Path root;
    private final NamedParameterJdbcTemplate db=mock(NamedParameterJdbcTemplate.class);
    private final GoldenSourceRightsRepository rights=mock(GoldenSourceRightsRepository.class);

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void privateDirectory(Path p) throws Exception {
        Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rwx------"));
    }

    private void put(Path p,byte[] content) throws Exception {
        Files.write(p,content);
        Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));
    }

    private Fno176PrivateIntakeRunner runner(KeyPair pair) throws Exception {
        privateDirectory(root);
        return new Fno176PrivateIntakeRunner(db,rights,root.toString(),
            Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }

    private Path bundle(KeyPair pair) throws Exception {
        var dir=Files.createDirectory(root.resolve("entry-0001"));
        privateDirectory(dir);
        var today=LocalDate.now(ZoneId.of("Asia/Kolkata"));
        var day=new Fno176OriginalObservedIngestor.VerifiedDay("UPSTOX_ANALYTICS",
            "NSE_FO|FIXTURE","NSE_INDEX|Nifty 50",today.minusDays(20),today.plusDays(30),
            75,today.minusDays(1),new BigDecimal("25000"),new BigDecimal("25300"),
            new BigDecimal("24900"),new BigDecimal("25100"),100000,200000,
            UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),today.plusDays(20));
        byte[] h={1,2},o={3,4},l={5,6};
        var unsigned=new Fno176AttestedSourceVerifier.Receipt(day,hash(h),hash(o),hash(l),
            Instant.now(),new byte[0]);
        var signer=Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(Fno176AttestedSourceVerifier.signingBytes(unsigned));
        var signed=new Fno176AttestedSourceVerifier.Receipt(day,unsigned.ohlcvSha256(),
            unsigned.oiSha256(),unsigned.listingSha256(),unsigned.reviewedAt(),signer.sign());
        put(dir.resolve("receipt.json"),new ObjectMapper().findAndRegisterModules()
            .writeValueAsBytes(signed));
        put(dir.resolve("ohlcv.bin"),h);
        put(dir.resolve("oi.bin"),o);
        put(dir.resolve("listing.bin"),l);
        return dir;
    }

    @Test void readsPrivateSignedBundleAndUsesExistingRightsAndDatabaseGate() throws Exception {
        var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var runner=runner(pair);
        assertEquals("NO_SOURCE_BUNDLES",runner.importOnce().state());
        bundle(pair);
        assertEquals(0,runner.importOnce().stored());
        verifyNoInteractions(db);
        when(rights.isPermitted(anyString(),anyString(),any(),any())).thenReturn(true);
        when(db.update(anyString(),any(MapSqlParameterSource.class))).thenReturn(1);
        var result=runner.importOnce();
        assertEquals("OBSERVATIONS_STORED_NOT_E2E",result.state());
        assertEquals(1,result.submitted());
        assertEquals(1,result.stored());
        assertEquals(0,result.rejected());
        verify(db).update(anyString(),any(MapSqlParameterSource.class));
    }

    @Test void rejectsTamperedPacketSymlinkAndUntrustedKey() throws Exception {
        var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var runner=runner(pair);
        var dir=bundle(pair);
        when(rights.isPermitted(anyString(),anyString(),any(),any())).thenReturn(true);
        assertEquals(0,runner(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
            .importOnce().stored());
        put(dir.resolve("ohlcv.bin"),new byte[]{9});
        assertEquals(0,runner.importOnce().stored());
        Files.delete(dir.resolve("ohlcv.bin"));
        Files.createSymbolicLink(dir.resolve("ohlcv.bin"),dir.resolve("oi.bin"));
        assertEquals(0,runner.importOnce().stored());
        verifyNoInteractions(db);
    }

    @Test void rejectsPublicDirectoryAndMissingConfig() throws Exception {
        var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var runner=runner(pair);
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxr-xr-x"));
        assertEquals("PRIVATE_INTAKE_UNAVAILABLE",runner.importOnce().state());
        assertEquals("CONFIGURATION_MISSING",
            new Fno176PrivateIntakeRunner(db,rights,"","").importOnce().state());
        verifyNoInteractions(db,rights);
    }
}
