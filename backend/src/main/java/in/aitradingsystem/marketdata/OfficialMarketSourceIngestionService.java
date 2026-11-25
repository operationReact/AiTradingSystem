package in.aitradingsystem.marketdata;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OfficialMarketSourceIngestionService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter NSE_DATE = DateTimeFormatter.ofPattern("dd-MM-uuuu", Locale.ROOT);
    private static final Set<String> SUPPORTED_SECTOR_INDICES = Set.of(
            "NIFTY_AUTO",
            "NIFTY_BANK",
            "NIFTY_CAPITAL_GOODS",
            "NIFTY_CHEMICALS",
            "NIFTY_CONSUMER_DURABLES",
            "NIFTY_FINANCIAL_SERVICES",
            "NIFTY_FMCG",
            "NIFTY_HEALTHCARE",
            "NIFTY_IT",
            "NIFTY_MEDIA",
            "NIFTY_METAL",
            "NIFTY_OIL_AND_GAS",
            "NIFTY_PHARMA",
            "NIFTY_POWER",
            "NIFTY_PRIVATE_BANK",
            "NIFTY_PSU_BANK",
            "NIFTY_REALTY",
            "NIFTY_TELECOMMUNICATIONS");

    private final OfficialMarketSourceArchiveService archive;
    private final OfficialMarketSourceTransport transport;
    private final Clock clock;
    private final URI nifty50ConstituentUri;
    private final URI nifty500ConstituentUri;
    private final URI indexHistoryEndpoint;
    private final URI vixHistoryEndpoint;
    private final URI nseBootstrapUri;
    private final int historyLookbackDays;
    private final List<String> sectorIndices;
    private List<URI> capSegmentUris = List.of(
            URI.create("https://www.niftyindices.com/IndexConstituent/ind_nifty100list.csv"),
            URI.create("https://www.niftyindices.com/IndexConstituent/ind_niftymidcap150list.csv"),
            URI.create("https://www.niftyindices.com/IndexConstituent/ind_niftysmallcap250list.csv"),
            URI.create("https://www.niftyindices.com/IndexConstituent/ind_niftymicrocap250_list.csv"));
    private MarketUniverseMembershipStore membership;

    public OfficialMarketSourceIngestionService(
            OfficialMarketSourceArchiveService archive,
            OfficialMarketSourceTransport transport,
            Clock clock,
            @Value("${app.market-data.official-sources.constituents-url:https://www.niftyindices.com/IndexConstituent/ind_nifty50list.csv}") String nifty50ConstituentUrl,
            @Value("${app.market-data.official-sources.nifty500-constituents-url:https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv}") String nifty500ConstituentUrl,
            @Value("${app.market-data.official-sources.index-history-endpoint:}") String indexHistoryEndpoint,
            @Value("${app.market-data.official-sources.vix-history-endpoint:}") String vixHistoryEndpoint,
            @Value("${app.market-data.official-sources.nse-bootstrap-url:https://www.nseindia.com/}") String nseBootstrapUrl,
            @Value("${app.market-data.official-sources.history-lookback-days:14}") int historyLookbackDays,
            @Value("${app.market-data.official-sources.sector-indices:}") String sectorIndices) {
        if (historyLookbackDays < 1 || historyLookbackDays > 366) {
            throw new IllegalArgumentException("Official source history lookback must be between 1 and 366 days");
        }
        this.archive = archive;
        this.transport = transport;
        this.clock = clock;
        this.nifty50ConstituentUri = requiredUri(nifty50ConstituentUrl, "NIFTY 50 constituent URL");
        this.nifty500ConstituentUri = requiredUri(nifty500ConstituentUrl, "NIFTY 500 constituent URL");
        this.indexHistoryEndpoint = optionalUri(indexHistoryEndpoint);
        this.vixHistoryEndpoint = optionalUri(vixHistoryEndpoint);
        this.nseBootstrapUri = requiredUri(nseBootstrapUrl, "NSE bootstrap URL");
        this.historyLookbackDays = historyLookbackDays;
        this.sectorIndices = parseSectorIndices(sectorIndices);
    }

    /** Official cap-segment lists (large/mid/small/micro), overridable per environment. */
    @Autowired(required = false)
    void configureCapSegmentSources(
            @Value("${app.market-data.official-sources.nifty100-constituents-url:https://www.niftyindices.com/IndexConstituent/ind_nifty100list.csv}") String nifty100,
            @Value("${app.market-data.official-sources.midcap150-constituents-url:https://www.niftyindices.com/IndexConstituent/ind_niftymidcap150list.csv}") String midcap150,
            @Value("${app.market-data.official-sources.smallcap250-constituents-url:https://www.niftyindices.com/IndexConstituent/ind_niftysmallcap250list.csv}") String smallcap250,
            @Value("${app.market-data.official-sources.microcap250-constituents-url:https://www.niftyindices.com/IndexConstituent/ind_niftymicrocap250_list.csv}") String microcap250) {
        this.capSegmentUris = List.of(
                requiredUri(nifty100, "NIFTY 100 constituent URL"),
                requiredUri(midcap150, "NIFTY MIDCAP 150 constituent URL"),
                requiredUri(smallcap250, "NIFTY SMALLCAP 250 constituent URL"),
                requiredUri(microcap250, "NIFTY MICROCAP 250 constituent URL"));
    }

    /** Optional: when present, each archived constituent list also refreshes market_universe_member. */
    @Autowired(required = false)
    void setMembershipStore(MarketUniverseMembershipStore membership) {
        this.membership = membership;
    }

    public List<SourceRequest> scheduledRequests() {
        LocalDate to = LocalDate.now(clock.withZone(MARKET_ZONE));
        LocalDate from = to.minusDays(historyLookbackDays);
        List<SourceRequest> requests = new ArrayList<>();
        requests.add(new SourceRequest(
                OfficialMarketSourceFamily.NIFTY50_CONSTITUENTS,
                "",
                nifty50ConstituentUri,
                null));
        requests.add(new SourceRequest(
                OfficialMarketSourceFamily.NIFTY500_CONSTITUENTS,
                "",
                nifty500ConstituentUri,
                null));
        OfficialMarketSourceFamily[] capFamilies = {
                OfficialMarketSourceFamily.NIFTY100_CONSTITUENTS,
                OfficialMarketSourceFamily.NIFTY_MIDCAP150_CONSTITUENTS,
                OfficialMarketSourceFamily.NIFTY_SMALLCAP250_CONSTITUENTS,
                OfficialMarketSourceFamily.NIFTY_MICROCAP250_CONSTITUENTS};
        for (int i = 0; i < capFamilies.length; i++) {
            requests.add(new SourceRequest(capFamilies[i], "", capSegmentUris.get(i), null));
        }

        if (indexHistoryEndpoint != null) {
            requests.add(indexHistoryRequest(OfficialMarketSourceFamily.NIFTY50_HISTORY, "", "NIFTY 50", from, to));
            for (String sectorIndex : sectorIndices) {
                requests.add(indexHistoryRequest(
                        OfficialMarketSourceFamily.SECTOR_INDEX_HISTORY,
                        sectorIndex,
                        sectorIndex.replace('_', ' '),
                        from,
                        to));
            }
        }
        if (vixHistoryEndpoint != null) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("from", NSE_DATE.format(from));
            query.put("to", NSE_DATE.format(to));
            query.put("csv", "true");
            requests.add(new SourceRequest(
                    OfficialMarketSourceFamily.INDIA_VIX_HISTORY,
                    "",
                    withQuery(vixHistoryEndpoint, query),
                    nseBootstrapUri));
        }
        return List.copyOf(requests);
    }

    public OfficialMarketSourceSnapshot ingest(SourceRequest request) {
        if (request == null) {
            throw new MarketDataException("OFFICIAL_SOURCE_REQUEST_REQUIRED",
                    "Official source ingestion request is required");
        }
        OfficialMarketSourceTransport.Response response = transport.get(
                request.sourceUri(),
                request.bootstrapUri(),
                request.family().maxBytes());
        Instant observedAt = clock.instant();
        OfficialMarketSourceSnapshot snapshot = archive.archive(
                request.family(),
                request.variant(),
                request.sourceUri().toString(),
                observedAt,
                response.contentType(),
                response.payload());
        // The raw archive is the evidence; membership is a derived, replaceable view of it.
        // A rejected list (wrong size, unexpected series) leaves the previous membership intact.
        if (membership != null && snapshot != null
                && IndexConstituentCsv.universeFor(request.family()).isPresent()) {
            membership.materialize(snapshot);
        }
        return snapshot;
    }

    private SourceRequest indexHistoryRequest(
            OfficialMarketSourceFamily family,
            String variant,
            String indexType,
            LocalDate from,
            LocalDate to) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("indexType", indexType);
        query.put("from", NSE_DATE.format(from));
        query.put("to", NSE_DATE.format(to));
        query.put("csv", "true");
        return new SourceRequest(family, variant, withQuery(indexHistoryEndpoint, query), nseBootstrapUri);
    }

    private URI withQuery(URI endpoint, Map<String, String> query) {
        if (endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Official source endpoint must not contain a query or fragment");
        }
        StringBuilder value = new StringBuilder(endpoint.toString()).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (!first) value.append('&');
            first = false;
            value.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            value.append('=');
            value.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return URI.create(value.toString());
    }

    private List<String> parseSectorIndices(String configured) {
        if (configured == null || configured.isBlank()) return List.of();
        List<String> values = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.ROOT).replace(' ', '_'))
                .distinct()
                .sorted()
                .toList();
        for (String value : values) {
            if (!SUPPORTED_SECTOR_INDICES.contains(value)) {
                throw new IllegalArgumentException("Unsupported official sector index: " + value);
            }
        }
        return values;
    }

    private URI requiredUri(String value, String label) {
        URI uri = optionalUri(value);
        if (uri == null) throw new IllegalArgumentException(label + " is required");
        return uri;
    }

    private URI optionalUri(String value) {
        if (value == null || value.isBlank()) return null;
        URI uri = URI.create(value.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Official source endpoints must be HTTPS URLs without user info");
        }
        return uri;
    }

    public record SourceRequest(
            OfficialMarketSourceFamily family,
            String variant,
            URI sourceUri,
            URI bootstrapUri) {
        public SourceRequest {
            if (family == null || sourceUri == null) {
                throw new IllegalArgumentException("Official source family and URI are required");
            }
            variant = variant == null ? "" : variant;
        }
    }
}
