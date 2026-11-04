package in.aitradingsystem.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freezes a real, DB-derived numerical feature vector from an existing V59 source
 * capture. No inference, publisher licensing, source authenticity or order action.
 */
@Service
public class MatchedCausalFeatureProjectionService {
    public static final String CONTRACT = "v4-java-causal-feature-projection.v1";
    private static final String SCHEMA = "features.market.v1";
    private static final MathContext DIVISION = MathContext.DECIMAL64;

    public record ProjectionView(
        String contractVersion, UUID sessionId, String caseKey, String symbol,
        String sourceInputFingerprint, String sourceCaptureFingerprint,
        long previousCandleId, UUID newsAiEvidenceId,
        String featureSchemaVersion, Instant featureAsOf,
        Instant latestFeatureAvailableAt, Map<String, Double> features,
        String featurePayloadFingerprint, Instant projectedAt, String projectedBy,
        boolean javaDatabaseBound, boolean sourceRightsVerified,
        boolean independentlyVerifiedModelInference, boolean canAuthorizeCapital) {}

    private record Source(
        String caseKey, String symbol, long candleId, UUID newsId,
        Instant marketAt, Instant newsAt, String marketHash, String newsHash,
        String inputHash, String captureHash, Instant capturedAt) {}

    private record Candle(
        long id, String symbol, String exchange, String symbolToken, String interval,
        Instant candleAt, BigDecimal open, BigDecimal high, BigDecimal low,
        BigDecimal close, long volume, String source, Instant fetchedAt) {}

    private record News(
        UUID id, String source, String contentHash, Instant publishedAt, Instant fetchedAt) {}

    private record Analysis(
        UUID id, Instant sourceAvailableAt, Instant generatedAt, BigDecimal direction,
        BigDecimal confidence, BigDecimal materiality, String responseFingerprint) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AuditService audit;
    private final TradingExecutionAuthority authority;
    private final MatchedSessionSafetyService safety;
    private final MatchedPaperShadowIntakeService intake;

    public MatchedCausalFeatureProjectionService(
            JdbcClient jdbc, ObjectMapper mapper, Clock clock,
            AuditService audit, TradingExecutionAuthority authority,
            MatchedSessionSafetyService safety, MatchedPaperShadowIntakeService intake) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.audit = audit;
        this.authority = authority;
        this.safety = safety;
        this.intake = intake;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ProjectionView freeze(UUID sessionId, String caseKey) {
        if (!TradingExecutionAuthority.PAPER.equals(authority.current().tradingMode())) {
            throw new IllegalStateException("Numerical feature projection requires PAPER mode");
        }
        if (sessionId == null || caseKey == null
                || !caseKey.matches("^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")) {
            throw new IllegalArgumentException("Valid sessionId and caseKey are required");
        }
        // Serializes PAUSE/QUARANTINE with new feature evidence.
        safety.requireActiveForAppend(sessionId);
        var session = intake.session(sessionId);
        Source source = source(sessionId, caseKey);
        Instant now = clock.instant();
        if (source.capturedAt().isAfter(now)) {
            throw new IllegalStateException("Source capture timestamp is in the future");
        }
        Candle candle = candle(source.candleId());
        Candle previous = jdbc.sql("""
                SELECT candle_id, trading_symbol, exchange, symbol_token, interval, candle_time,
                       open_price, high_price, low_price, close_price, volume,
                       source, fetched_at
                  FROM market_candle
                 WHERE trading_symbol = :symbol AND exchange = :exchange
                   AND symbol_token = :token AND interval = :interval AND candle_time <= :candleTime
                   AND fetched_at <= :capturedAt
                 ORDER BY candle_time DESC, candle_id DESC
                 LIMIT 1
                """)
            .param("symbol", source.symbol()).param("exchange", candle.exchange())
            .param("token", candle.symbolToken()).param("interval", candle.interval())
            .param("candleTime", at(candle.candleAt().minus(intervalDuration(candle.interval()))))
            .param("capturedAt", at(source.capturedAt()))
            .query((rs, n) -> mapCandle(rs))
            .optional()
            .orElseThrow(() -> new IllegalStateException(
                "Prior available market candle required; cannot infer zero return"));
        News news = jdbc.sql("""
                SELECT id, source, content_hash, published_at, fetched_at
                  FROM news_item
                 WHERE id = :newsId AND :symbol = ANY(linked_symbols)
                """)
            .param("newsId", source.newsId()).param("symbol", source.symbol())
            .query((rs, n) -> new News(
                rs.getObject("id", UUID.class), rs.getString("source"),
                rs.getString("content_hash"),
                instant(rs, "published_at"), instant(rs, "fetched_at")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException(
                "Frozen news record no longer matches the source instrument"));

        String marketHash = ModelRegistryService.fingerprint(
            ProspectiveMatchedSourceService.CONTRACT, Long.toString(candle.id()),
            candle.symbol(), candle.exchange(), candle.interval(),
            candle.candleAt().toString(), candle.open().toPlainString(),
            candle.high().toPlainString(), candle.low().toPlainString(),
            candle.close().toPlainString(), Long.toString(candle.volume()),
            candle.source(), candle.fetchedAt().toString());
        String newsHash = ModelRegistryService.fingerprint(
            ProspectiveMatchedSourceService.CONTRACT, news.id().toString(), news.source(),
            news.contentHash(), news.publishedAt().toString(), news.fetchedAt().toString());
        String inputHash = ModelRegistryService.fingerprint(
            ProspectiveMatchedSourceService.CONTRACT, session.enrollmentFingerprint(),
            caseKey, marketHash, newsHash);
        String captureHash = ModelRegistryService.fingerprint(
            ProspectiveMatchedSourceService.CONTRACT, sessionId.toString(),
            caseKey, Long.toString(candle.id()), news.id().toString(),
            marketHash, newsHash, inputHash);
        if (!source.symbol().equals(candle.symbol())
                || !source.marketAt().equals(candle.fetchedAt())
                || !source.newsAt().equals(news.fetchedAt())
                || !source.marketHash().equals(marketHash)
                || !source.newsHash().equals(newsHash)
                || !source.inputHash().equals(inputHash)
                || !source.captureHash().equals(captureHash)) {
            throw new IllegalStateException("Frozen V59 source bytes have changed; refuse inference");
        }

        Analysis analysis = jdbc.sql("""
                SELECT id, source_available_at, generated_at,
                       direction_score, confidence, materiality, response_fingerprint
                  FROM news_ai_evidence
                 WHERE news_id = :newsId
                   AND generated_at <= :capturedAt
                   AND :symbol = ANY(impacted_symbols)
                 ORDER BY generated_at DESC, id DESC
                 LIMIT 1
                """)
            .param("newsId", news.id()).param("capturedAt", at(source.capturedAt()))
            .param("symbol", source.symbol())
            .query((rs, n) -> new Analysis(
                rs.getObject("id", UUID.class),
                instant(rs, "source_available_at"), instant(rs, "generated_at"),
                rs.getBigDecimal("direction_score"), rs.getBigDecimal("confidence"),
                rs.getBigDecimal("materiality"), rs.getString("response_fingerprint")))
            .optional()
            .orElseThrow(() -> new IllegalStateException(
                "No pre-capture AI news analysis for source and instrument"));

        if (!analysis.sourceAvailableAt().equals(news.fetchedAt())
                || analysis.generatedAt().isBefore(analysis.sourceAvailableAt())
                || analysis.generatedAt().isAfter(source.capturedAt())
                || previous.fetchedAt().isAfter(source.capturedAt())
                || candle.close().signum() <= 0 || previous.close().signum() <= 0
                || previous.volume() <= 0 || candle.high().compareTo(candle.low()) < 0) {
            throw new IllegalStateException("Causal feature preconditions failed");
        }

        var features = new TreeMap<String, Double>();
        features.put("market.return_1",
            candle.close().divide(previous.close(), DIVISION)
                .subtract(BigDecimal.ONE).doubleValue());
        features.put("market.range_pct",
            candle.high().subtract(candle.low()).divide(candle.close(), DIVISION)
                .doubleValue());
        features.put("market.volume_change_1",
            BigDecimal.valueOf(candle.volume())
                .divide(BigDecimal.valueOf(previous.volume()), DIVISION)
                .subtract(BigDecimal.ONE).doubleValue());
        features.put("event.AI_NEWS_EVIDENCE.direction_score",
            analysis.direction().doubleValue());
        features.put("event.AI_NEWS_EVIDENCE.confidence",
            analysis.confidence().doubleValue());
        features.put("event.AI_NEWS_EVIDENCE.materiality",
            analysis.materiality().doubleValue());

        if (features.size() != 6 || features.values().stream()
                .anyMatch(value -> !Double.isFinite(value) || Math.abs(value) > 1e12)) {
            throw new IllegalStateException("Invalid or unbounded trained model feature vector");
        }
        Instant latestAvailableAt = max(
            candle.fetchedAt(), previous.fetchedAt(), analysis.generatedAt(), news.fetchedAt());
        if (latestAvailableAt.isAfter(source.capturedAt())) {
            throw new IllegalStateException("Late feature became available after frozen source");
        }
        List<String> parts = new ArrayList<>(List.of(
            CONTRACT, session.enrollmentFingerprint(), source.captureHash(),
            Long.toString(previous.id()), analysis.id().toString(),
            analysis.responseFingerprint(), latestAvailableAt.toString()));
        for (var item : features.entrySet()) {
            parts.add(item.getKey());
            parts.add(Double.toString(item.getValue()));
        }
        String fingerprint = ModelRegistryService.fingerprint(parts.toArray(String[]::new));
        var existing = jdbc.sql("""
                SELECT feature_payload_fingerprint
                  FROM v4_matched_feature_projection
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query(String.class).optional();
        if (existing.isPresent()) {
            if (!existing.get().equals(fingerprint)) {
                throw new IllegalStateException("Frozen feature projection conflicts with changed data");
            }
            return byCase(sessionId, caseKey);
        }

        int decisionsAlreadyRecorded = jdbc.sql("""
                SELECT COUNT(*) FROM v4_matched_case
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query(Integer.class).single();
        if (decisionsAlreadyRecorded != 0) {
            throw new IllegalStateException("Cannot backfill model features after candidate decisions");
        }

        String actor = actor();
        jdbc.sql("""
                INSERT INTO v4_matched_feature_projection (
                    session_id, case_key, source_capture_fingerprint,
                    previous_candle_id, news_ai_evidence_id, feature_schema_version,
                    feature_as_of, latest_feature_available_at,
                    feature_json, feature_payload_fingerprint,
                    projected_at, projected_by)
                VALUES (
                    :sessionId, :caseKey, :captureHash,
                    :previousId, :newsEvidenceId, :schema,
                    :featureAsOf, :latestAvailableAt,
                    CAST(:features AS jsonb), :fingerprint,
                    :projectedAt, :actor)
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .param("captureHash", source.captureHash())
            .param("previousId", previous.id()).param("newsEvidenceId", analysis.id())
            .param("schema", SCHEMA)
            .param("featureAsOf", at(now)).param("latestAvailableAt", at(latestAvailableAt))
            .param("features", json(features)).param("fingerprint", fingerprint)
            .param("projectedAt", at(now)).param("actor", actor)
            .update();
        audit.record("V4_PAPER_SHADOW_FEATURES_FROZEN", "V4_PAPER_SHADOW_FEATURE",
            sessionId + ":" + caseKey, Map.of(
                "sessionId", sessionId.toString(),
                "caseKey", caseKey, "featureFingerprint", fingerprint,
                "canAuthorizeCapital", false));
        return byCase(sessionId, caseKey);
    }

    @Transactional(readOnly = true)
    public ProjectionView byCase(UUID sessionId, String caseKey) {
        return jdbc.sql("""
                SELECT p.session_id, p.case_key, s.symbol, s.input_fingerprint,
                       p.source_capture_fingerprint, p.previous_candle_id,
                       p.news_ai_evidence_id, p.feature_schema_version,
                       p.feature_as_of, p.latest_feature_available_at, p.feature_json,
                       p.feature_payload_fingerprint, p.projected_at, p.projected_by
                  FROM v4_matched_feature_projection p
                  JOIN v4_matched_source_capture s
                    ON s.session_id = p.session_id AND s.case_key = p.case_key
                 WHERE p.session_id = :sessionId AND p.case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query((rs, row) -> new ProjectionView(
                CONTRACT, rs.getObject("session_id", UUID.class),
                rs.getString("case_key"), rs.getString("symbol"),
                rs.getString("input_fingerprint"),
                rs.getString("source_capture_fingerprint"),
                rs.getLong("previous_candle_id"),
                rs.getObject("news_ai_evidence_id", UUID.class),
                rs.getString("feature_schema_version"),
                instant(rs, "feature_as_of"),
                instant(rs, "latest_feature_available_at"),
                parseJson(rs.getString("feature_json")),
                rs.getString("feature_payload_fingerprint"),
                instant(rs, "projected_at"),
                rs.getString("projected_by"),
                true, false, false, false))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown frozen feature projection"));
    }

    private Source source(UUID sessionId, String caseKey) {
        return jdbc.sql("""
                SELECT case_key, symbol, candle_id, news_id, market_available_at,
                       news_available_at, market_snapshot_fingerprint, news_snapshot_fingerprint,
                       input_fingerprint, capture_fingerprint, captured_at
                  FROM v4_matched_source_capture
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query((rs, row) -> new Source(
                rs.getString("case_key"), rs.getString("symbol"),
                rs.getLong("candle_id"), rs.getObject("news_id", UUID.class),
                instant(rs, "market_available_at"), instant(rs, "news_available_at"),
                rs.getString("market_snapshot_fingerprint"),
                rs.getString("news_snapshot_fingerprint"),
                rs.getString("input_fingerprint"), rs.getString("capture_fingerprint"),
                instant(rs, "captured_at")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Missing V59 source capture"));
    }

    private static Duration intervalDuration(String value) {
        return switch (value) {
            case "ONE_MINUTE" -> Duration.ofMinutes(1);
            case "THREE_MINUTE" -> Duration.ofMinutes(3);
            case "FIVE_MINUTE" -> Duration.ofMinutes(5);
            case "TEN_MINUTE" -> Duration.ofMinutes(10);
            case "FIFTEEN_MINUTE" -> Duration.ofMinutes(15);
            case "THIRTY_MINUTE" -> Duration.ofMinutes(30);
            case "ONE_HOUR" -> Duration.ofHours(1);
            case "ONE_DAY" -> Duration.ofDays(1);
            default -> throw new IllegalArgumentException("Unsupported previous candle interval");
        };
    }

    private Candle candle(long id) {
        return jdbc.sql("""
                SELECT candle_id, trading_symbol, exchange, symbol_token, interval, candle_time,
                       open_price, high_price, low_price, close_price, volume,
                       source, fetched_at
                  FROM market_candle WHERE candle_id = :id
                """)
            .param("id", id)
            .query((rs, n) -> mapCandle(rs))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Original market candle unavailable"));
    }

    private static Candle mapCandle(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Candle(
            rs.getLong("candle_id"), rs.getString("trading_symbol"),
            rs.getString("exchange"), rs.getString("symbol_token"),
            rs.getString("interval"),
            instant(rs, "candle_time"),
            rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"),
            rs.getBigDecimal("low_price"), rs.getBigDecimal("close_price"),
            rs.getLong("volume"), rs.getString("source"),
            instant(rs, "fetched_at"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant max(Instant... values) {
        Instant newest = values[0];
        for (Instant value : values) {
            if (value.isAfter(newest)) newest = value;
        }
        return newest;
    }

    private String json(Map<String, Double> features) {
        try {
            return mapper.writeValueAsString(features);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Feature projection could not be serialized", ex);
        }
    }

    private Map<String, Double> parseJson(String value) {
        try {
            return Map.copyOf(mapper.readValue(value, new TypeReference<Map<String, Double>>() {}));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Frozen feature JSON could not be loaded", ex);
        }
    }

    private static String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return "SYSTEM";
        }
        String name = authentication.getName();
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalStateException("Invalid authenticated actor");
        }
        return name;
    }
}
