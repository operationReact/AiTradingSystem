package in.aitradingsystem.marketdata;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class OfficialMarketSourceArchiveService {
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "nseindia.com",
            "www.nseindia.com",
            "nsearchives.nseindia.com",
            "niftyindices.com",
            "www.niftyindices.com");
    private static final Pattern VARIANT = Pattern.compile("[A-Z0-9_]{2,80}");
    private static final Set<String> CONTENT_TYPES = Set.of(
            "text/csv",
            "text/plain",
            "application/json",
            "application/octet-stream");

    private final OfficialMarketSourceArchiveRepository repository;

    public OfficialMarketSourceArchiveService(OfficialMarketSourceArchiveRepository repository) {
        this.repository = repository;
    }

    public OfficialMarketSourceSnapshot archive(
            OfficialMarketSourceFamily family,
            String variant,
            String sourceUrl,
            Instant observedAt,
            String contentType,
            byte[] payload) {
        if (family == null) {
            throw invalid("OFFICIAL_SOURCE_FAMILY_REQUIRED", "Official source family is required");
        }
        String normalizedVariant = normalizeVariant(family, variant);
        String normalizedUrl = validateUrl(sourceUrl);
        if (observedAt == null || observedAt.isAfter(Instant.now().plusSeconds(300))) {
            throw invalid("INVALID_OFFICIAL_SOURCE_OBSERVED_AT",
                    "Official source observedAt must not be missing or in the future");
        }
        String normalizedContentType = normalizeContentType(contentType);
        if (payload == null || payload.length == 0) {
            throw invalid("EMPTY_OFFICIAL_SOURCE_PAYLOAD", "Official source payload is empty");
        }
        if (payload.length > family.maxBytes()) {
            throw invalid("OFFICIAL_SOURCE_PAYLOAD_TOO_LARGE",
                    "Official source payload exceeds the configured family limit");
        }
        String sha256 = sha256(payload);
        return repository.saveFirstObserved(
                UUID.randomUUID(),
                family,
                normalizedVariant,
                normalizedUrl,
                observedAt,
                sha256,
                normalizedContentType,
                payload.clone());
    }

    public OfficialMarketSourceSnapshot requireLatestAt(
            OfficialMarketSourceFamily family,
            String variant,
            Instant asOf) {
        if (family == null || asOf == null) {
            throw invalid("INVALID_OFFICIAL_SOURCE_QUERY", "Official source family and asOf are required");
        }
        String normalizedVariant = normalizeVariant(family, variant);
        OfficialMarketSourceSnapshot snapshot = repository.latestAt(family, normalizedVariant, asOf);
        if (snapshot == null) {
            throw new MarketDataException("OFFICIAL_SOURCE_NOT_AVAILABLE_AT_AS_OF",
                    "No archived official source snapshot was available at the requested asOf");
        }
        return snapshot;
    }

    private String normalizeVariant(OfficialMarketSourceFamily family, String variant) {
        String normalized = variant == null ? "" : variant.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (family.variantRequired()) {
            if (!VARIANT.matcher(normalized).matches()) {
                throw invalid("INVALID_OFFICIAL_SOURCE_VARIANT",
                        "Sector-index source variant must be a normalized bounded index code");
            }
            return normalized;
        }
        if (!normalized.isEmpty()) {
            throw invalid("OFFICIAL_SOURCE_VARIANT_NOT_ALLOWED",
                    "This official source family does not accept a source variant");
        }
        return "";
    }

    private String validateUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank() || sourceUrl.length() > 2048) {
            throw invalid("INVALID_OFFICIAL_SOURCE_URL", "Official source URL is invalid");
        }
        try {
            URI uri = new URI(sourceUrl.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    || uri.getUserInfo() != null) {
                throw invalid("OFFICIAL_SOURCE_URL_NOT_ALLOWED",
                        "Official source URL must use an allowlisted HTTPS NSE host");
            }
            return uri.toString();
        } catch (URISyntaxException ex) {
            throw invalid("INVALID_OFFICIAL_SOURCE_URL", "Official source URL is invalid");
        }
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank() || contentType.length() > 120) {
            throw invalid("INVALID_OFFICIAL_SOURCE_CONTENT_TYPE", "Official source content type is invalid");
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        String mediaType = normalized.split(";", 2)[0].trim();
        if (!CONTENT_TYPES.contains(mediaType)) {
            throw invalid("OFFICIAL_SOURCE_CONTENT_TYPE_NOT_ALLOWED",
                    "Official source content type is not an accepted research payload type");
        }
        return normalized;
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private MarketDataException invalid(String code, String message) {
        return new MarketDataException(code, message);
    }
}
