package in.aitradingsystem.marketdata;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapter for an independently reviewed, signed source observation.
 * A pinned reviewer public key is supplied by trusted application composition,
 * never by an API request or by a bundle. No default key or permission grant.
 * The signature binds exact original bytes and every normalized V102 field.
 * This is forward observation only: the existing ingestor records DB time,
 * not any historical availability asserted by the caller.
 */
public final class Fno176AttestedSourceVerifier
        implements Fno176OriginalObservedIngestor.IndependentlyVerifiedSource {
    private static final String VERSION="minttick.fno176.original-observation.v1";
    private final PublicKey trustedReviewer;
    private final Clock clock;
    private final Receipt receipt;

    public record Receipt(Fno176OriginalObservedIngestor.VerifiedDay day,
                          String ohlcvSha256, String oiSha256, String listingSha256,
                          Instant reviewedAt, byte[] signature) {
        public Receipt {
            signature=signature==null ? new byte[0] : signature.clone();
        }
        @Override public byte[] signature(){return signature.clone();}
    }

    public Fno176AttestedSourceVerifier(PublicKey trustedReviewer, Clock clock, Receipt receipt) {
        this.trustedReviewer=Objects.requireNonNull(trustedReviewer);
        this.clock=Objects.requireNonNull(clock);
        this.receipt=Objects.requireNonNull(receipt);
    }

    @Override
    public Optional<Fno176OriginalObservedIngestor.VerifiedDay> verify(
            Fno176OriginalObservedIngestor.OriginalPackets packets) {
        try {
            var day=receipt.day();
            var now=clock.instant();
            if (packets==null || day==null || !packets.providerCode().equals(day.providerCode())
                    || receipt.reviewedAt()==null || receipt.reviewedAt().isAfter(now)
                    || receipt.reviewedAt().isBefore(now.minus(7,ChronoUnit.DAYS))
                    || receipt.signature().length!=64
                    || !hash(packets.ohlcvPacket()).equals(receipt.ohlcvSha256())
                    || !hash(packets.finalOiPacket()).equals(receipt.oiSha256())
                    || !hash(packets.contractMasterPacket()).equals(receipt.listingSha256()))
                return Optional.empty();
            var verifier=Signature.getInstance("Ed25519");
            verifier.initVerify(trustedReviewer);
            verifier.update(signingBytes(receipt));
            return verifier.verify(receipt.signature()) ? Optional.of(day) : Optional.empty();
        } catch (GeneralSecurityException | IOException | RuntimeException invalid) {
            return Optional.empty();
        }
    }

    /** Versioned, unambiguous encoding shared with a separately trusted reviewer. */
    static byte[] signingBytes(Receipt r) throws IOException {
        var d=Objects.requireNonNull(r.day());
        var bytes=new ByteArrayOutputStream();
        try (var out=new DataOutputStream(bytes)) {
            for (var field:new String[]{VERSION,d.providerCode(),d.instrumentKey(),
                    d.underlyingKey(),d.listedFrom().toString(),d.expiry().toString(),
                    Integer.toString(d.lotSize()),d.sessionDate().toString(),
                    price(d.open()),price(d.high()),price(d.low()),price(d.close()),
                    Long.toString(d.volumeUnits()),Long.toString(d.finalOiUnits()),
                    d.calendarEvidenceId().toString(),d.ohlcvInferenceRightsId().toString(),
                    d.oiInferenceRightsId().toString(),d.retainUntil().toString(),
                    r.ohlcvSha256(),r.oiSha256(),r.listingSha256(),r.reviewedAt().toString()}) {
                var utf8=Objects.requireNonNull(field).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (utf8.length>1024) throw new IllegalArgumentException("SOURCE_FIELD_UNBOUNDED");
                out.writeInt(utf8.length);
                out.write(utf8);
            }
        }
        return bytes.toByteArray();
    }

    private static String price(BigDecimal value) {
        Objects.requireNonNull(value);
        if (value.scale()< -14 || value.scale()>6 || value.precision()>20)
            throw new IllegalArgumentException("SOURCE_PRICE_UNBOUNDED");
        return value.toPlainString();
    }

    private static String hash(byte[] bytes) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
