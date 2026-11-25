package in.aitradingsystem.instrument;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class InstrumentCatalogueResearchService {
    static final String CONTRACT_VERSION = "instrument-catalogue.v1";
    static final String STATUS = "RESEARCH_CATALOGUE_ONLY";
    static final int MAX_ROWS = 50_000;
    private static final List<String> CASH_EXCHANGES = List.of("BSE", "NSE");
    private static final String CASH_INSTRUMENT_TYPE = "EQ";

    private final InstrumentCatalogueResearchRepository repository;

    public InstrumentCatalogueResearchService(InstrumentCatalogueResearchRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the latest successful catalogue that was actually available at {@code asOf}.
     * Future catalogue imports are ignored and purged historical catalogue rows fail closed.
     */
    public InstrumentCatalogueResearchExport exportCashCatalogue(Instant asOf, int maxRows) {
        if (asOf == null) {
            throw new InstrumentMasterException("INVALID_AS_OF", "Research catalogue asOf is required");
        }
        if (asOf.isAfter(Instant.now().plusSeconds(300))) {
            throw new InstrumentMasterException("INVALID_AS_OF", "Research catalogue asOf cannot be in the future");
        }
        if (maxRows < 1 || maxRows > MAX_ROWS) {
            throw new InstrumentMasterException("INVALID_RESEARCH_LIMIT",
                    "Research catalogue maxRows must be between 1 and " + MAX_ROWS);
        }

        InstrumentCatalogueResearchRepository.CatalogueResearchVersion version = repository.requireVersionAt(asOf);
        List<InstrumentCatalogueResearchExport.ResearchInstrument> instruments =
                repository.cashEquities(version.version(), maxRows + 1);
        if (instruments.isEmpty()) {
            throw new InstrumentMasterException("CATALOGUE_NOT_RETAINED",
                    "The point-in-time catalogue metadata exists but its cash-equity rows are not retained");
        }
        if (instruments.size() > maxRows) {
            throw new InstrumentMasterException("RESEARCH_CATALOGUE_TOO_LARGE",
                    "The point-in-time cash catalogue exceeds the requested maxRows bound");
        }

        String fingerprint = fingerprint(version, instruments);
        return new InstrumentCatalogueResearchExport(
                CONTRACT_VERSION,
                Instant.now(),
                asOf,
                version.availableAt(),
                version.version(),
                version.availableAt(),
                version.sourceUrl(),
                version.acceptedCount(),
                version.rejectedCount(),
                CASH_EXCHANGES,
                CASH_INSTRUMENT_TYPE,
                instruments.size(),
                fingerprint,
                STATUS,
                false,
                instruments);
    }

    /** Fingerprint excludes generatedAt/asOf so the same catalogue payload has one stable identity. */
    static String fingerprint(InstrumentCatalogueResearchRepository.CatalogueResearchVersion version,
                              List<InstrumentCatalogueResearchExport.ResearchInstrument> instruments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                write(out, CONTRACT_VERSION);
                write(out, version.version().toString());
                write(out, version.availableAt().toString());
                write(out, version.sourceUrl());
                out.writeInt(version.acceptedCount());
                out.writeInt(version.rejectedCount());
                out.writeInt(instruments.size());
                for (InstrumentCatalogueResearchExport.ResearchInstrument instrument : instruments) {
                    out.writeLong(instrument.instrumentId());
                    write(out, instrument.exchangeSegment());
                    write(out, instrument.exchange());
                    write(out, instrument.symbolToken());
                    write(out, instrument.tradingSymbol());
                    writeNullable(out, instrument.name());
                    writeNullable(out, instrument.instrumentType());
                    writeNullable(out, instrument.expiry());
                    writeNullable(out, instrument.strike());
                    out.writeInt(instrument.lotSize());
                    write(out, instrument.tickSize().stripTrailingZeros().toPlainString());
                }
            }
            return HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
        } catch (NoSuchAlgorithmException | IOException ex) {
            throw new IllegalStateException("Unable to fingerprint instrument catalogue", ex);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] encoded = Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static void writeNullable(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) write(out, value);
    }

    private static void writeNullable(DataOutputStream out, LocalDate value) throws IOException {
        writeNullable(out, value == null ? null : value.toString());
    }

    private static void writeNullable(DataOutputStream out, BigDecimal value) throws IOException {
        writeNullable(out, value == null ? null : value.stripTrailingZeros().toPlainString());
    }
}
