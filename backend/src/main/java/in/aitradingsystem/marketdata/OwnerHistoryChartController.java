package in.aitradingsystem.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.instrument.InstrumentMasterService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner's private saved Angel One ONE_DAY chart, independent of the public
 * market_candle/Golden Source store. Only bounded SHA-checked host exports
 * are visible in the backend, never the root-only archive, its SQLite or keys.
 */
@RestController
@RequestMapping("/api/customer/owner-history/v1")
public final class OwnerHistoryChartController {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_CACHE_BYTES = 90_000;
    private static final int MAX_BARS = 400;
    private final String ownerSubject;
    private final Path charts;
    private final Path monthlyCharts;
    private final ObjectMapper mapper;
    private final InstrumentMasterService instruments;

    public OwnerHistoryChartController(
            @Value("${MINTTICK_ANGEL_ONE_OWNER_SUB:}") String ownerSubject,
            @Value("${MINTTICK_OWNER_HISTORY_CHART_PATH:/run/minttick-owner-history/charts}") String chartsPath,
            @Value("${MINTTICK_OWNER_HISTORY_MONTHLY_PATH:/run/minttick-owner-history/monthly}") String monthlyPath,
            ObjectMapper mapper, InstrumentMasterService instruments) {
        this.ownerSubject = ownerSubject;
        this.charts = Path.of(chartsPath);
        this.monthlyCharts = Path.of(monthlyPath);
        this.mapper = mapper;
        this.instruments = instruments;
    }

    @GetMapping("/candles")
    public ResponseEntity<CustomerMarketCandleController.CustomerCandlePage> candles(
            Authentication authentication,
            @RequestParam long instrumentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return readCandles(authentication, instrumentId, fromDate, toDate, false);
    }

    @GetMapping("/monthly-candles")
    public ResponseEntity<CustomerMarketCandleController.CustomerCandlePage> monthlyCandles(
            Authentication authentication,
            @RequestParam long instrumentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return readCandles(authentication, instrumentId, fromDate, toDate, true);
    }

    private ResponseEntity<CustomerMarketCandleController.CustomerCandlePage> readCandles(
            Authentication authentication, long instrumentId,
            LocalDate fromDate, LocalDate toDate, boolean monthly) {
        // No fallback to an API key, another customer's JWT, or a shared candle table.
        if (ownerSubject.isBlank() || !(authentication instanceof JwtAuthenticationToken jwt)
                || !ownerSubject.equals(jwt.getToken().getSubject())
                || jwt.getAuthorities().stream().noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner-only chart");
        }
        if (instrumentId < 1 || fromDate == null || toDate == null || fromDate.isAfter(toDate)
                || Duration.between(fromDate.atStartOfDay(IST),
                    toDate.plusDays(1).atStartOfDay(IST)).toDays() > (monthly ? 11000 : 367)
                || toDate.isAfter(LocalDate.now(IST))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid chart window");
        }
        final Instrument instrument;
        try {
            instrument = instruments.requireActiveInstrument(instrumentId);
        } catch (InstrumentMasterException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Instrument unavailable");
        }
        String token = instrument.symbolToken();
        if (!"NSE".equals(instrument.exchange()) || instrument.tradingSymbol() == null
                || !instrument.tradingSymbol().endsWith("-EQ")
                || token == null || !token.matches("[0-9]{1,12}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No owner cash-equity archive");
        }
        // Both directories are sanitized, SHA-source-verified snapshots under
        // the existing private read-only monitor mount, never the raw archive.
        Path directory = monthly ? monthlyCharts : charts;
        Path file = directory.resolve(token + ".json");
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Owner chart not exported");
        }
        try {
            if (Files.size(file) > MAX_CACHE_BYTES) throw new IOException("Oversize chart");
            JsonNode data = mapper.readTree(Files.readAllBytes(file));
            if (data == null ||
                    !(monthly ? "minttick.owner.chart.monthly.v1" : "minttick.owner.chart.v1")
                        .equals(data.path("schema").asText())
                    || !(monthly ? "ONE_MONTH" : "ONE_DAY").equals(data.path("interval").asText())
                    || (monthly && (!"ONE_DAY".equals(data.path("sourceInterval").asText())
                        || !"FIRST_OPEN_MAX_HIGH_MIN_LOW_LAST_CLOSE_SUM_VOLUME"
                             .equals(data.path("aggregation").asText())
                        || !"UNVERIFIED".equals(data.path("sessionCompleteness").asText())))
                    || !"NSE".equals(data.path("exchange").asText())
                    || !instrument.tradingSymbol().equals(data.path("symbol").asText())
                    || !token.equals(data.path("token").asText())
                    || !data.path("rows").isArray() || data.path("rows").size() > MAX_BARS) {
                throw new IOException("Invalid owner chart");
            }
            Instant exportedAt = OffsetDateTime.parse(data.path("generatedAt").asText()).toInstant();
            Instant now = Instant.now();
            if (exportedAt.isAfter(now.plusSeconds(120))
                    || exportedAt.isBefore(now.minus(Duration.ofHours(48)))) {
                throw new IOException("Stale owner chart");
            }
            List<CustomerMarketCandleController.CustomerCandle> bars = new ArrayList<>();
            Instant prior = null;
            for (JsonNode row : data.path("rows")) {
                if (!row.isArray() || row.size() != 6) throw new IOException("Invalid bar");
                Instant timestamp = OffsetDateTime.parse(row.get(0).asText()).toInstant();
                BigDecimal open = positive(row.get(1));
                BigDecimal high = positive(row.get(2));
                BigDecimal low = positive(row.get(3));
                BigDecimal close = positive(row.get(4));
                JsonNode volume = row.get(5);
                if (high.compareTo(open.max(close)) < 0 || low.compareTo(open.min(close)) > 0
                        || low.compareTo(high) > 0 || !volume.canConvertToLong()
                        || volume.longValue() < 0
                        || prior != null && !timestamp.isAfter(prior)) {
                    throw new IOException("Inconsistent bar");
                }
                prior = timestamp;
                LocalDate session = timestamp.atZone(IST).toLocalDate();
                if (!session.isBefore(fromDate) && !session.isAfter(toDate)) {
                    bars.add(new CustomerMarketCandleController.CustomerCandle(
                        timestamp, open, high, low, close, volume.longValue(),
                        monthly ? "ANGEL_ONE_OWNER_PRIVATE_ARCHIVE_MONTHLY_FROM_DAILY"
                                : "ANGEL_ONE_OWNER_PRIVATE_ARCHIVE", exportedAt));
                }
            }
            var page = new CustomerMarketCandleController.CustomerCandlePage(
                instrumentId, monthly ? "ONE_MONTH" : "ONE_DAY",
                List.copyOf(bars), 0, MAX_BARS, bars.size(), false);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
        } catch (IOException | DateTimeException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Owner chart archive verification unavailable");
        }
    }

    private static BigDecimal positive(JsonNode value) throws IOException {
        if (!value.isNumber()) throw new IOException("Invalid OHLC");
        BigDecimal result = value.decimalValue();
        if (result.signum() <= 0) throw new IOException("Invalid OHLC");
        return result;
    }
}
