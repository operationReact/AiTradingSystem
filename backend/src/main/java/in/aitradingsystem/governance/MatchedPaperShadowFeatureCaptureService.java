package in.aitradingsystem.governance;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks Java V59 market/news custody into a six-feature, predecision research snapshot
 * for real (but unapproved) CatBoost/LightGBM weights. No broker/model calls here.
 */
@Service
public class MatchedPaperShadowFeatureCaptureService {
    public static final String CONTRACT = "v4-java-prospective-research-features.v1";
    private static final MathContext MATH = MathContext.DECIMAL64;
    private static final int DECIMALS = 16;

    private record Candle(
        long id, String symbol, String exchange, String token, String interval,
        Instant candleTime, BigDecimal open, BigDecimal high, BigDecimal low,
        BigDecimal close, long volume, String source, Instant fetchedAt) {}
    private record News(
        UUID id, String source, String contentHash, Instant publishedAt, Instant fetchedAt) {}
    private record AiEvidence(
        UUID id, Instant generatedAt, Instant sourceAt, BigDecimal direction,
        BigDecimal confidence, BigDecimal materiality) {}

    public record FeatureCapture(
        String contractVersion, UUID sessionId, String caseKey, String symbol,
        String sourceInputFingerprint, String featurePayloadFingerprint,
        Instant featureAsOf, Instant latestFeatureAvailableAt,
        long previousCandleId, UUID newsAiEvidenceId,
        Map<String, BigDecimal> features, Instant recordedAt, String recordedBy,
        boolean javaDatabaseBound, boolean sourceRightsVerified,
        boolean providerSignatureVerified, boolean modelInferenceExecuted,
        boolean canAuthorizeCapital) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuditService audit;
    private final TradingExecutionAuthority authority;
    private final ProspectiveMatchedSourceService sources;
    private final MatchedPaperShadowIntakeService intake;
    private final MatchedSessionSafetyService safety;
    private final ModelEmergencyControlService emergency;

    public MatchedPaperShadowFeatureCaptureService(
            JdbcClient jdbc, Clock clock, AuditService audit,
            TradingExecutionAuthority authority, ProspectiveMatchedSourceService sources,
            MatchedPaperShadowIntakeService intake, MatchedSessionSafetyService safety,
            ModelEmergencyControlService emergency) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.authority = authority;
        this.sources = sources;
        this.intake = intake;
        this.safety = safety;
        this.emergency = emergency;
    }

    @Transactional
    public FeatureCapture freeze(UUID sessionId, String caseKey) {
        if (!TradingExecutionAuthority.PAPER.equals(authority.current().tradingMode())) {
            throw new IllegalStateException("PAPER-only feature preparation");
        }
        // Lock lifecycle state through commit; cannot race with a session quarantine.
        safety.requireActiveForAppend(sessionId);
        var enrollment = intake.session(sessionId);
        for (var participant : enrollment.models()) {
            emergency.requireModelAllowed(participant.modelVersionId());
        }
        var frozen = sources.byCase(sessionId, caseKey);
        var previous = jdbc.sql("""
                SELECT 1 FROM v4_matched_feature_capture
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query(Integer.class).optional();
        if (previous.isPresent()) return byCase(sessionId, caseKey);

        int decisions = jdbc.sql("""
                SELECT COUNT(*) FROM v4_matched_case
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query(Integer.class).single();
        if (decisions != 0) {
            throw new IllegalStateException("Cannot invent historical features after a model decision");
        }

        Instant asOf = frozen.capturedAt();
        Candle latest = jdbc.sql("""
                SELECT candle_id, trading_symbol, exchange, symbol_token, interval,
                       candle_time, open_price, high_price, low_price, close_price,
                       volume, source, fetched_at
                  FROM market_candle WHERE candle_id = :id
                """)
            .param("id", frozen.candleId())
            .query((rs, row) -> candle(rs))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Frozen market candle missing"));
        News news = jdbc.sql("""
                SELECT id, source, content_hash, published_at, fetched_at
                  FROM news_item
                 WHERE id = :id AND :symbol = ANY(linked_symbols)
                """)
            .param("id", frozen.newsId()).param("symbol", frozen.symbol())
            .query((rs, row) -> new News(
                rs.getObject("id", UUID.class), rs.getString("source"),
                rs.getString("content_hash"),
                utc(rs.getObject("published_at", OffsetDateTime.class)),
                utc(rs.getObject("fetched_at", OffsetDateTime.class))))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Frozen linked news missing"));

        String marketHash = ModelRegistryService.fingerprint(
            ProspectiveMatchedSourceService.CONTRACT,
            Long.toString(latest.id()), latest.symbol(), latest.exchange(), latest.interval(),
            latest.candleTime().toString(), latest.open().toPlainString(),
            latest.high().toPlainString(), latest.low().toPlainString(),
            latest.close().toPlainString(), Long.toString(latest.volume()),
            latest.source(), latest.fetchedAt().toString());
        String newsHash = ModelRegistryService.fingerprint(
            ProspectiveMatchedSourceService.CONTRACT,
            news.id().toString(), news.source(), news.contentHash(),
            news.publishedAt().toString(), news.fetchedAt().toString());
        if (!latest.symbol().equals(frozen.symbol())
                || !latest.fetchedAt().equals(frozen.marketAvailableAt())
                || !news.fetchedAt().equals(frozen.newsAvailableAt())
                || !marketHash.equals(frozen.marketSnapshotFingerprint())
                || !newsHash.equals(frozen.newsSnapshotFingerprint())) {
            throw new IllegalStateException("Captured source content changed since frozen Java custody");
        }

        Candle before = jdbc.sql("""
                SELECT candle_id, trading_symbol, exchange, symbol_token, interval,
                       candle_time, open_price, high_price, low_price, close_price,
                       volume, source, fetched_at
                  FROM market_candle
                 WHERE exchange = :exchange AND symbol_token = :token
                   AND interval = :interval AND candle_time < :candleTime
                   AND fetched_at <= :asOf
                 ORDER BY candle_time DESC, candle_id DESC LIMIT 1
                """)
            .param("exchange", latest.exchange()).param("token", latest.token())
            .param("interval", latest.interval())
            .param("candleTime", timestamp(latest.candleTime()))
            .param("asOf", timestamp(asOf))
            .query((rs, row) -> candle(rs))
            .optional()
            .orElseThrow(() -> new IllegalStateException(
                "No eligible earlier bar available before frozen source capture"));

        AiEvidence ai = jdbc.sql("""
                SELECT id, generated_at, source_available_at,
                       direction_score, confidence, materiality
                  FROM news_ai_evidence
                 WHERE news_id = :newsId
                   AND generated_at <= :asOf
                   AND source_available_at <= generated_at
                 ORDER BY generated_at DESC, id DESC LIMIT 1
                """)
            .param("newsId", frozen.newsId()).param("asOf", timestamp(asOf))
            .query((rs, row) -> new AiEvidence(
                rs.getObject("id", UUID.class),
                utc(rs.getObject("generated_at", OffsetDateTime.class)),
                utc(rs.getObject("source_available_at", OffsetDateTime.class)),
                rs.getBigDecimal("direction_score"), rs.getBigDecimal("confidence"),
                rs.getBigDecimal("materiality")))
            .optional()
            .orElseThrow(() -> new IllegalStateException(
                "No pre-capture generated news AI features for this source"));

        Instant latestAvailable = List.of(
            latest.fetchedAt(), before.fetchedAt(), news.fetchedAt(), ai.generatedAt())
            .stream().max(Instant::compareTo).orElseThrow();
        if (latestAvailable.isAfter(asOf) || ai.sourceAt().isBefore(news.fetchedAt())
                || ai.generatedAt().isBefore(ai.sourceAt())) {
            throw new IllegalStateException("Feature data was not available by source capture");
        }
        if (latest.close().signum() <= 0 || before.close().signum() <= 0
                || latest.high().compareTo(latest.low()) < 0) {
            throw new IllegalStateException("Unusable market prices for model inputs");
        }
        Map<String, BigDecimal> features = new TreeMap<>();
        features.put("market.return_1", precise(
            latest.close().divide(before.close(), MATH).subtract(BigDecimal.ONE)));
        features.put("market.range_pct", precise(
            latest.high().subtract(latest.low()).divide(latest.close(), MATH)));
        features.put("market.volume_change_1", before.volume() > 0
            ? precise(BigDecimal.valueOf(latest.volume())
                .divide(BigDecimal.valueOf(before.volume()), MATH)
                .subtract(BigDecimal.ONE))
            : BigDecimal.ZERO.setScale(DECIMALS));
        features.put("event.AI_NEWS_EVIDENCE.direction_score", precise(ai.direction()));
        features.put("event.AI_NEWS_EVIDENCE.confidence", precise(ai.confidence()));
        features.put("event.AI_NEWS_EVIDENCE.materiality", precise(ai.materiality()));

        List<String> fingerprintParts = new ArrayList<>(List.of(
            CONTRACT, frozen.captureFingerprint(), frozen.inputFingerprint(),
            Long.toString(before.id()), ai.id().toString(), asOf.toString(),
            latestAvailable.toString()));
        for (var entry : features.entrySet()) {
            fingerprintParts.add(entry.getKey());
            fingerprintParts.add(entry.getValue().toPlainString());
        }
        String featureHash = ModelRegistryService.fingerprint(
            fingerprintParts.toArray(String[]::new));
        Instant recorded = clock.instant();
        if (recorded.isBefore(asOf)) {
            throw new IllegalStateException("Feature capture timestamp precedes source snapshot");
        }
        String actor = currentActor();
        jdbc.sql("""
                INSERT INTO v4_matched_feature_capture (
                    session_id, case_key, previous_candle_id, news_ai_evidence_id,
                    source_input_fingerprint, feature_payload_fingerprint,
                    feature_as_of, latest_feature_available_at,
                    market_return_1, market_range_pct, market_volume_change_1,
                    news_direction_score, news_confidence, news_materiality,
                    recorded_at, recorded_by)
                VALUES (
                    :sessionId, :caseKey, :previousId, :aiId, :sourceHash, :featuresHash,
                    :asOf, :availableAt, :return, :range, :volume,
                    :direction, :confidence, :materiality, :recorded, :actor)
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .param("previousId", before.id()).param("aiId", ai.id())
            .param("sourceHash", frozen.inputFingerprint())
            .param("featuresHash", featureHash)
            .param("asOf", timestamp(asOf)).param("availableAt", timestamp(latestAvailable))
            .param("return", features.get("market.return_1"))
            .param("range", features.get("market.range_pct"))
            .param("volume", features.get("market.volume_change_1"))
            .param("direction", features.get("event.AI_NEWS_EVIDENCE.direction_score"))
            .param("confidence", features.get("event.AI_NEWS_EVIDENCE.confidence"))
            .param("materiality", features.get("event.AI_NEWS_EVIDENCE.materiality"))
            .param("recorded", timestamp(recorded)).param("actor", actor)
            .update();
        audit.record("V4_MATCHED_MODEL_FEATURE_SNAPSHOT_FROZEN", "V4_MODEL_FEATURES",
            sessionId + ":" + caseKey, Map.of(
                "sessionId", sessionId.toString(),
                "featureFingerprint", featureHash,
                "canAuthorizeCapital", false));
        return byCase(sessionId, caseKey);
    }

    @Transactional(readOnly = true)
    public FeatureCapture byCase(UUID sessionId, String caseKey) {
        return jdbc.sql("""
                SELECT c.symbol, f.source_input_fingerprint, f.feature_payload_fingerprint,
                       f.feature_as_of, f.latest_feature_available_at,
                       f.previous_candle_id, f.news_ai_evidence_id,
                       f.market_return_1, f.market_range_pct, f.market_volume_change_1,
                       f.news_direction_score, f.news_confidence, f.news_materiality,
                       f.recorded_at, f.recorded_by
                  FROM v4_matched_feature_capture f
                  JOIN v4_matched_source_capture c
                    ON c.session_id = f.session_id AND c.case_key = f.case_key
                 WHERE f.session_id = :sessionId AND f.case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query((rs, row) -> {
                Map<String, BigDecimal> features = new TreeMap<>();
                features.put("market.return_1", rs.getBigDecimal("market_return_1"));
                features.put("market.range_pct", rs.getBigDecimal("market_range_pct"));
                features.put("market.volume_change_1", rs.getBigDecimal("market_volume_change_1"));
                features.put("event.AI_NEWS_EVIDENCE.direction_score",
                    rs.getBigDecimal("news_direction_score"));
                features.put("event.AI_NEWS_EVIDENCE.confidence",
                    rs.getBigDecimal("news_confidence"));
                features.put("event.AI_NEWS_EVIDENCE.materiality",
                    rs.getBigDecimal("news_materiality"));
                return new FeatureCapture(
                    CONTRACT, sessionId, caseKey, rs.getString("symbol"),
                    rs.getString("source_input_fingerprint"),
                    rs.getString("feature_payload_fingerprint"),
                    utc(rs.getObject("feature_as_of", OffsetDateTime.class)),
                    utc(rs.getObject("latest_feature_available_at", OffsetDateTime.class)),
                    rs.getLong("previous_candle_id"),
                    rs.getObject("news_ai_evidence_id", UUID.class),
                    Map.copyOf(features),
                    utc(rs.getObject("recorded_at", OffsetDateTime.class)),
                    rs.getString("recorded_by"), true, false, false, false, false);
            })
            .optional()
            .orElseThrow(() -> new NoSuchElementException(
                "No frozen model feature bundle for this matched case"));
    }

    private static BigDecimal precise(BigDecimal number) {
        BigDecimal rounded = number.setScale(DECIMALS, RoundingMode.HALF_UP);
        if (rounded.abs().compareTo(new BigDecimal("1000000000000")) > 0) {
            throw new IllegalStateException("Unbounded model feature");
        }
        return rounded;
    }

    private static Candle candle(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Candle(
            rs.getLong("candle_id"), rs.getString("trading_symbol"),
            rs.getString("exchange"), rs.getString("symbol_token"),
            rs.getString("interval"),
            utc(rs.getObject("candle_time", OffsetDateTime.class)),
            rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"),
            rs.getBigDecimal("low_price"), rs.getBigDecimal("close_price"),
            rs.getLong("volume"), rs.getString("source"),
            utc(rs.getObject("fetched_at", OffsetDateTime.class)));
    }

    private static Instant utc(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return "SYSTEM";
        }
        String name = auth.getName();
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalStateException("Invalid model evidence actor");
        }
        return name;
    }
}
