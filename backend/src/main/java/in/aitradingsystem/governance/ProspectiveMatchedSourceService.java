package in.aitradingsystem.governance;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freezes source rows already persisted in Java before a PAPER/SHADOW inference
 * attempt. Only database custody is established; no independent feed signature,
 * source licence, model computation, or brokerage execution is implied.
 */
@Service
public class ProspectiveMatchedSourceService {
    public static final String CONTRACT = "v4-java-db-source-capture.v1";
    private static final String CASE = "^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$";
    private static final Duration MARKET_MAX_AGE = Duration.ofMinutes(30);
    private static final Duration NEWS_MAX_AGE = Duration.ofHours(24);

    public record CaptureInput(String caseKey, long candleId, UUID newsId) {}
    public record CaptureView(
        String contractVersion, UUID sessionId, String caseKey, String symbol,
        long candleId, UUID newsId, Instant marketAvailableAt, Instant newsAvailableAt,
        String marketSnapshotFingerprint, String newsSnapshotFingerprint,
        String inputFingerprint, String captureFingerprint, Instant capturedAt,
        String capturedBy, boolean javaDatabaseBound, boolean providerSignatureVerified,
        boolean sourceRightsVerified, boolean inferenceExecuted, boolean canAuthorizeCapital) {}

    private record Candle(
        long id, String symbol, String exchange, String interval, Instant candleTime,
        BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume,
        String source, Instant fetchedAt) {}
    private record News(
        UUID id, String source, String contentHash, Instant publishedAt, Instant fetchedAt) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuditService audit;
    private final TradingExecutionAuthority authority;
    private final MatchedPaperShadowIntakeService intake;
    private final ModelEmergencyControlService emergency;

    public ProspectiveMatchedSourceService(
            JdbcClient jdbc, Clock clock, AuditService audit, TradingExecutionAuthority authority,
            MatchedPaperShadowIntakeService intake, ModelEmergencyControlService emergency) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.authority = authority;
        this.intake = intake;
        this.emergency = emergency;
    }

    @Transactional
    public CaptureView capture(UUID sessionId, CaptureInput input) {
        if (!TradingExecutionAuthority.PAPER.equals(authority.current().tradingMode())) {
            throw new IllegalStateException("PAPER/SHADOW source capture requires PAPER mode");
        }
        if (input == null || input.caseKey() == null || !input.caseKey().matches(CASE)
                || input.candleId() <= 0 || input.newsId() == null) {
            throw new IllegalArgumentException("Valid caseKey, candleId and newsId are required");
        }
        var session = intake.session(sessionId);
        for (var model : session.models()) {
            emergency.requireModelAllowed(model.modelVersionId());
        }
        Instant now = clock.instant();
        Candle candle = jdbc.sql("""
                SELECT candle_id, trading_symbol, exchange, interval, candle_time,
                       open_price, high_price, low_price, close_price,
                       volume, source, fetched_at
                  FROM market_candle WHERE candle_id = :id
                """)
            .param("id", input.candleId())
            .query((rs, row) -> new Candle(
                rs.getLong("candle_id"), rs.getString("trading_symbol"),
                rs.getString("exchange"), rs.getString("interval"),
                instant(rs, "candle_time"),
                rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"),
                rs.getBigDecimal("low_price"), rs.getBigDecimal("close_price"),
                rs.getLong("volume"), rs.getString("source"), instant(rs, "fetched_at")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("No persisted market candle"));
        if (!candle.symbol().matches("^[A-Z0-9._-]{2,30}$")) {
            throw new IllegalArgumentException("Only matching bounded cash-equity symbols are supported");
        }
        News news = jdbc.sql("""
                SELECT id, source, content_hash, published_at, fetched_at
                  FROM news_item
                 WHERE id = :id
                   AND :symbol = ANY(linked_symbols)
                """)
            .param("id", input.newsId())
            .param("symbol", candle.symbol())
            .query((rs, row) -> new News(
                rs.getObject("id", UUID.class), rs.getString("source"),
                rs.getString("content_hash"), instantNullable(rs, "published_at"),
                instant(rs, "fetched_at")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("No news item linked to the market symbol"));

        if (now.isBefore(session.createdAt())
                || candle.candleTime().isAfter(candle.fetchedAt())
                || news.publishedAt() == null || news.publishedAt().isAfter(news.fetchedAt())
                || candle.fetchedAt().isAfter(now) || news.fetchedAt().isAfter(now)
                || Duration.between(candle.fetchedAt(), now).compareTo(MARKET_MAX_AGE) > 0
                || Duration.between(news.fetchedAt(), now).compareTo(NEWS_MAX_AGE) > 0) {
            throw new IllegalArgumentException("Future, stale or causally unavailable Java source evidence");
        }

        String marketHash = ModelRegistryService.fingerprint(
            CONTRACT, Long.toString(candle.id()), candle.symbol(), candle.exchange(),
            candle.interval(), candle.candleTime().toString(), candle.open().toPlainString(),
            candle.high().toPlainString(), candle.low().toPlainString(),
            candle.close().toPlainString(), Long.toString(candle.volume()),
            candle.source(), candle.fetchedAt().toString());
        String newsHash = ModelRegistryService.fingerprint(
            CONTRACT, news.id().toString(), news.source(), news.contentHash(),
            news.publishedAt().toString(), news.fetchedAt().toString());
        String inputHash = ModelRegistryService.fingerprint(
            CONTRACT, session.enrollmentFingerprint(), input.caseKey(),
            marketHash, newsHash);
        String captureHash = ModelRegistryService.fingerprint(
            CONTRACT, sessionId.toString(), input.caseKey(), Long.toString(candle.id()),
            news.id().toString(), marketHash, newsHash, inputHash);

        var previous = jdbc.sql("""
                SELECT capture_fingerprint FROM v4_matched_source_capture
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", input.caseKey())
            .query(String.class).optional();
        if (previous.isPresent()) {
            if (!previous.get().equals(captureHash)) {
                throw new IllegalStateException("Conflicting frozen source capture or changed upstream row");
            }
            return byCase(sessionId, input.caseKey());
        }
        int priorDecision = jdbc.sql("""
                SELECT count(*) FROM v4_matched_case
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", input.caseKey())
            .query(Integer.class).single();
        if (priorDecision != 0) {
            throw new IllegalStateException("Cannot backfill source evidence after model decisions");
        }

        String actor = actor();
        jdbc.sql("""
                INSERT INTO v4_matched_source_capture (
                    session_id, case_key, candle_id, news_id, symbol, market_available_at,
                    news_available_at, market_snapshot_fingerprint, news_snapshot_fingerprint,
                    input_fingerprint, capture_fingerprint, captured_at, captured_by)
                VALUES (
                    :sessionId, :caseKey, :candleId, :newsId, :symbol, :marketAt,
                    :newsAt, :marketHash, :newsHash, :inputHash, :captureHash,
                    :capturedAt, :capturedBy)
                """)
            .param("sessionId", sessionId).param("caseKey", input.caseKey())
            .param("candleId", candle.id()).param("newsId", news.id())
            .param("symbol", candle.symbol())
            .param("marketAt", at(candle.fetchedAt())).param("newsAt", at(news.fetchedAt()))
            .param("marketHash", marketHash).param("newsHash", newsHash)
            .param("inputHash", inputHash).param("captureHash", captureHash)
            .param("capturedAt", at(now)).param("capturedBy", actor)
            .update();
        audit.record("V4_PAPER_SHADOW_DB_SOURCE_CAPTURED", "V4_PAPER_SHADOW_SOURCE",
            sessionId + ":" + input.caseKey(), Map.of(
                "sessionId", sessionId.toString(), "caseKey", input.caseKey(),
                "captureFingerprint", captureHash, "canAuthorizeCapital", false));
        return byCase(sessionId, input.caseKey());
    }

    @Transactional(readOnly = true)
    public CaptureView byCase(UUID sessionId, String caseKey) {
        return jdbc.sql("""
                SELECT session_id, case_key, symbol, candle_id, news_id, market_available_at,
                       news_available_at, market_snapshot_fingerprint, news_snapshot_fingerprint,
                       input_fingerprint, capture_fingerprint, captured_at, captured_by
                  FROM v4_matched_source_capture
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query((rs, row) -> new CaptureView(
                CONTRACT, rs.getObject("session_id", UUID.class),
                rs.getString("case_key"), rs.getString("symbol"),
                rs.getLong("candle_id"), rs.getObject("news_id", UUID.class),
                instant(rs, "market_available_at"), instant(rs, "news_available_at"),
                rs.getString("market_snapshot_fingerprint"),
                rs.getString("news_snapshot_fingerprint"),
                rs.getString("input_fingerprint"), rs.getString("capture_fingerprint"),
                instant(rs, "captured_at"), rs.getString("captured_by"),
                true, false, false, false, false))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown frozen source capture"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant instantNullable(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime at(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return "SYSTEM";
        }
        String name = authentication.getName();
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalStateException("Invalid audit actor");
        }
        return name;
    }
}
