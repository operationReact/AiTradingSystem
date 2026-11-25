package in.aitradingsystem.canonical;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Source-neutral, point-in-time candle input contract. Not an executable quote. */
public final class CanonicalCandleInput {
    public static final String SCHEMA_VERSION = "minttick.canonical.candle-input.v1";

    public enum Use { STORE, RESEARCH, TRAIN, DISPLAY }
    public enum Permission { VERIFIED, DENIED, UNKNOWN }

    public record Rights(Permission store, Permission research, Permission train, Permission display,
                         String grantEvidenceId) {
        public Rights {
            Objects.requireNonNull(store);
            Objects.requireNonNull(research);
            Objects.requireNonNull(train);
            Objects.requireNonNull(display);
            if (store == Permission.VERIFIED || research == Permission.VERIFIED
                    || train == Permission.VERIFIED || display == Permission.VERIFIED) {
                require(grantEvidenceId, "grantEvidenceId");
            }
        }
        public boolean permits(Use use) {
            return switch (Objects.requireNonNull(use)) {
                case STORE -> store == Permission.VERIFIED;
                case RESEARCH -> research == Permission.VERIFIED;
                case TRAIN -> train == Permission.VERIFIED;
                case DISPLAY -> display == Permission.VERIFIED;
            };
        }
    }

    public record Candle(String instrumentId, String listingEpisodeId, String exchange,
                         String interval, String provider, String datasetVersion,
                         Instant exchangeTimestamp, Instant acquiredAt, Instant availableAt,
                         BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                         long volume, String checksum, String sourceEvidenceId, Rights rights) {
        public Candle {
            require(instrumentId, "instrumentId");
            require(listingEpisodeId, "listingEpisodeId");
            require(exchange, "exchange");
            require(interval, "interval");
            require(provider, "provider");
            require(datasetVersion, "datasetVersion");
            require(checksum, "checksum");
            require(sourceEvidenceId, "sourceEvidenceId");
            Objects.requireNonNull(exchangeTimestamp);
            Objects.requireNonNull(acquiredAt);
            Objects.requireNonNull(availableAt);
            Objects.requireNonNull(rights);
            if (exchangeTimestamp.isAfter(availableAt) || availableAt.isAfter(acquiredAt))
                throw new IllegalArgumentException("invalid event/availability/acquisition ordering");
            Objects.requireNonNull(open);
            Objects.requireNonNull(high);
            Objects.requireNonNull(low);
            Objects.requireNonNull(close);
            if (volume < 0 || low.signum() <= 0 || open.signum() <= 0
                    || high.signum() <= 0 || close.signum() <= 0
                    || low.compareTo(high) > 0 || open.compareTo(low) < 0
                    || open.compareTo(high) > 0 || close.compareTo(low) < 0
                    || close.compareTo(high) > 0)
                throw new IllegalArgumentException("invalid OHLCV");
        }

        /** Eligibility is scoped by permitted use and observation time; no broker execution semantics. */
        public boolean eligible(Use use, Instant asOf) {
            return rights.permits(use) && !availableAt.isAfter(Objects.requireNonNull(asOf));
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }

    private CanonicalCandleInput() { }
}
