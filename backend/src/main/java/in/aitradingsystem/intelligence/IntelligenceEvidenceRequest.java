package in.aitradingsystem.intelligence;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Versioned point-in-time request sent to the Python intelligence plane. */
public record IntelligenceEvidenceRequest(
        @JsonProperty("contract_version") String contractVersion,
        @JsonProperty("request_id") String requestId,
        String symbol,
        @JsonProperty("as_of") Instant asOf,
        @JsonProperty("data_cutoff") Instant dataCutoff,
        @JsonProperty("feature_schema_version") String featureSchemaVersion,
        Map<String, BigDecimal> features,
        List<ProvenanceRef> provenance) {

    public static final String CONTRACT_VERSION = "intelligence.v1";

    public IntelligenceEvidenceRequest {
        contractVersion = require(contractVersion, "contractVersion");
        if (!CONTRACT_VERSION.equals(contractVersion)) throw new IllegalArgumentException("Unsupported intelligence contract version");
        requestId = require(requestId, "requestId");
        symbol = require(symbol, "symbol");
        featureSchemaVersion = require(featureSchemaVersion, "featureSchemaVersion");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(dataCutoff, "dataCutoff");
        if (dataCutoff.isAfter(asOf)) throw new IllegalArgumentException("dataCutoff cannot be later than asOf");
        features = features == null ? Map.of() : Map.copyOf(features);
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    public record ProvenanceRef(String source, String version,
                                @JsonProperty("observed_at") Instant observedAt,
                                String fingerprint) {
        public ProvenanceRef {
            source = require(source, "source");
            version = require(version, "version");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }
}
