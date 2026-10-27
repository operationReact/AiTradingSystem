package in.aitradingsystem.instrument;

import in.aitradingsystem.config.AngelOneProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class InstrumentMasterService {
    private final AngelOneProperties properties;
    private final InstrumentMasterDownloader downloader;
    private final InstrumentMasterParser parser;
    private final InstrumentMasterRepository repository;

    public InstrumentMasterService(AngelOneProperties properties, InstrumentMasterDownloader downloader,
                                   InstrumentMasterParser parser, InstrumentMasterRepository repository) {
        this.properties = properties;
        this.downloader = downloader;
        this.parser = parser;
        this.repository = repository;
    }

    public InstrumentImportSummary importLatest() {
        return importSource(null);
    }

    public InstrumentImportSummary importSource(byte[] providedForTestOnly) {
        UUID id = UUID.randomUUID();
        String source = properties.instrumentMasterUrl();
        repository.createImportRun(id, source);
        byte[] bytes = new byte[0];
        InstrumentMasterParser.ParseResult parsed = null;
        int duplicateCount = 0;
        try {
            bytes = providedForTestOnly == null ? downloader.download() : providedForTestOnly;
            if (bytes.length > properties.instrumentMasterMaxBytes()) {
                throw new InstrumentMasterException("SOURCE_TOO_LARGE", "Instrument-master download exceeds configured size limit");
            }
            parsed = parser.parse(bytes, properties.instrumentMasterMaxRows());
            List<InstrumentMasterParser.InstrumentDraft> unique = deduplicate(parsed.accepted());
            duplicateCount = parsed.accepted().size() - unique.size();

            double rejectedPercent = parsed.parsedCount() == 0 ? 100.0
                    : parsed.rejectedCount() * 100.0d / parsed.parsedCount();
            if (rejectedPercent > properties.instrumentMasterMaxRejectedPercent()) {
                throw new InstrumentMasterException("TOO_MANY_REJECTED_ROWS",
                        "Instrument master exceeded the configured invalid-row percentage");
            }
            if (unique.isEmpty()) {
                throw new InstrumentMasterException("NO_VALID_INSTRUMENTS", "No valid instruments were found");
            }

            int count = repository.publishCatalogue(id, unique, bytes.length, parsed.parsedCount(),
                    parsed.rejectedCount(), duplicateCount, properties.instrumentMasterMinRetainedPercent());
            return repository.importSummary(id);
        } catch (InstrumentMasterException e) {
            repository.failImport(id, bytes.length, parsed == null ? 0 : parsed.parsedCount(),
                    parsed == null ? 0 : parsed.accepted().size(), parsed == null ? 0 : parsed.rejectedCount(),
                    duplicateCount, e.code(), e.getMessage());
            return repository.importSummary(id);
        } catch (RuntimeException e) {
            repository.failImport(id, bytes.length, parsed == null ? 0 : parsed.parsedCount(),
                    parsed == null ? 0 : parsed.accepted().size(), parsed == null ? 0 : parsed.rejectedCount(),
                    duplicateCount, "IMPORT_FAILED", "Instrument master import failed; active catalogue was not changed");
            return repository.importSummary(id);
        }
    }

    public InstrumentSearchResponse search(String q, String exchange, String type, int page, int size) {
        if (page < 0 || page > 1_000_000) {
            throw new InstrumentMasterException("INVALID_PAGE", "Page must be between 0 and 1000000");
        }
        if (size < 1 || size > properties.instrumentSearchMaxPageSize()) {
            throw new InstrumentMasterException("INVALID_PAGE_SIZE",
                    "Page size must be between 1 and " + properties.instrumentSearchMaxPageSize());
        }
        return repository.search(q, exchange, type, page, size);
    }

    public Instrument requireActiveInstrument(long instrumentId) {
        if (instrumentId < 1) {
            throw new InstrumentMasterException("INSTRUMENT_NOT_FOUND", "Instrument ID is invalid");
        }
        return repository.findActiveInstrumentById(instrumentId);
    }

    public InstrumentMasterRepository.CatalogueSummary catalogueSummary() {
        return repository.catalogueSummary();
    }

    public InstrumentImportSummary importSummary(UUID id) {
        return repository.importSummary(id);
    }

    public List<InstrumentImportSummary> recentImports(int limit) {
        if (limit < 1 || limit > 100) throw new InstrumentMasterException("INVALID_LIMIT", "Limit must be between 1 and 100");
        return repository.recentImports(limit);
    }

    private List<InstrumentMasterParser.InstrumentDraft> deduplicate(
            List<InstrumentMasterParser.InstrumentDraft> input) {
        Map<Identity, InstrumentMasterParser.InstrumentDraft> seen = new HashMap<>();
        List<InstrumentMasterParser.InstrumentDraft> result = new ArrayList<>(input.size());
        for (InstrumentMasterParser.InstrumentDraft item : input) {
            Identity key = new Identity(item.exchangeSegment(), item.symbolToken(), item.tradingSymbol(),
                    item.expiry() == null ? "" : item.expiry().toString(),
                    item.strike() == null ? null : item.strike().stripTrailingZeros());
            InstrumentMasterParser.InstrumentDraft previous = seen.putIfAbsent(key, item);
            if (previous == null) {
                result.add(item);
            } else if (!samePayload(previous, item)) {
                throw new InstrumentMasterException("CONFLICTING_DUPLICATE",
                        "Instrument master contains conflicting rows for the same instrument identity");
            }
        }
        return List.copyOf(result);
    }

    private boolean samePayload(InstrumentMasterParser.InstrumentDraft a,
                                InstrumentMasterParser.InstrumentDraft b) {
        return java.util.Objects.equals(a.exchange(), b.exchange())
                && java.util.Objects.equals(a.name(), b.name())
                && java.util.Objects.equals(a.instrumentType(), b.instrumentType())
                && java.util.Objects.equals(a.expiry(), b.expiry())
                && decimalEqual(a.strike(), b.strike())
                && a.lotSize() == b.lotSize()
                && decimalEqual(a.tickSize(), b.tickSize());
    }

    private boolean decimalEqual(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private record Identity(String segment, String token, String symbol, String expiry, BigDecimal strike) {}
}
