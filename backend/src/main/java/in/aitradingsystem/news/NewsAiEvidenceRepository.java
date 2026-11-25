package in.aitradingsystem.news;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NewsAiEvidenceRepository {
    private final JdbcTemplate jdbc;

    public NewsAiEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsByRequestFingerprint(String requestFingerprint) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM news_ai_evidence WHERE request_fingerprint = ?",
            Integer.class,
            requestFingerprint);
        return count != null && count > 0;
    }

    public void save(NewsAiEvidence evidence) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO news_ai_evidence (
                    id, news_id, provider, model, provider_request_id, source_available_at,
                    generated_at, prompt_hash, request_fingerprint, response_fingerprint,
                    sentiment, direction_score, category, confidence, materiality, horizon,
                    novelty, contradiction, ambiguity, impacted_symbols, evidence, risks,
                    input_tokens, output_tokens, total_tokens, latency_ms)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (request_fingerprint) DO NOTHING
                """);
            int i = 1;
            statement.setString(i++, evidence.id());
            statement.setString(i++, evidence.newsId());
            statement.setString(i++, evidence.provider());
            statement.setString(i++, evidence.model());
            statement.setString(i++, evidence.providerRequestId());
            statement.setTimestamp(i++, Timestamp.from(evidence.sourceAvailableAt()));
            statement.setTimestamp(i++, Timestamp.from(evidence.generatedAt()));
            statement.setString(i++, evidence.promptHash());
            statement.setString(i++, evidence.requestFingerprint());
            statement.setString(i++, evidence.responseFingerprint());
            statement.setString(i++, evidence.sentiment());
            statement.setBigDecimal(i++, evidence.directionScore());
            statement.setString(i++, evidence.category());
            statement.setBigDecimal(i++, evidence.confidence());
            statement.setBigDecimal(i++, evidence.materiality());
            statement.setString(i++, evidence.horizon());
            statement.setBigDecimal(i++, evidence.novelty());
            statement.setBoolean(i++, evidence.contradiction());
            statement.setBoolean(i++, evidence.ambiguity());
            statement.setArray(i++, connection.createArrayOf("text", evidence.impactedSymbols().toArray()));
            statement.setString(i++, evidence.evidence());
            statement.setArray(i++, connection.createArrayOf("text", evidence.risks().toArray()));
            if (evidence.inputTokens() == null) statement.setNull(i++, java.sql.Types.INTEGER);
            else statement.setInt(i++, evidence.inputTokens());
            if (evidence.outputTokens() == null) statement.setNull(i++, java.sql.Types.INTEGER);
            else statement.setInt(i++, evidence.outputTokens());
            if (evidence.totalTokens() == null) statement.setNull(i++, java.sql.Types.INTEGER);
            else statement.setInt(i++, evidence.totalTokens());
            statement.setInt(i, evidence.latencyMs());
            return statement;
        });
    }

    public List<NewsAiEvidence> researchEvidence(Instant fromInclusive, Instant toExclusive, int limit) {
        return jdbc.query("""
            SELECT e.id::text, e.news_id::text, e.provider, e.model, e.provider_request_id,
                   e.source_available_at, e.generated_at, e.prompt_hash, e.request_fingerprint,
                   e.response_fingerprint, e.sentiment, e.direction_score, e.category,
                   e.confidence, e.materiality, e.horizon, e.novelty, e.contradiction,
                   e.ambiguity, e.impacted_symbols, e.evidence, e.risks,
                   e.input_tokens, e.output_tokens, e.total_tokens, e.latency_ms
            FROM news_ai_evidence e
            WHERE e.generated_at >= ? AND e.generated_at < ?
            ORDER BY e.generated_at, e.id
            LIMIT ?
            """, (rs, row) -> {
            java.sql.Array symbolsArray = rs.getArray("impacted_symbols");
            List<String> symbols = new ArrayList<>();
            if (symbolsArray != null) {
                for (Object value : (Object[]) symbolsArray.getArray()) symbols.add(String.valueOf(value));
                symbolsArray.free();
            }
            java.sql.Array risksArray = rs.getArray("risks");
            List<String> risks = new ArrayList<>();
            if (risksArray != null) {
                for (Object value : (Object[]) risksArray.getArray()) risks.add(String.valueOf(value));
                risksArray.free();
            }
            return new NewsAiEvidence(
                rs.getString("id"),
                rs.getString("news_id"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("provider_request_id"),
                rs.getTimestamp("source_available_at").toInstant(),
                rs.getTimestamp("generated_at").toInstant(),
                rs.getString("prompt_hash"),
                rs.getString("request_fingerprint"),
                rs.getString("response_fingerprint"),
                rs.getString("sentiment"),
                rs.getBigDecimal("direction_score"),
                rs.getString("category"),
                rs.getBigDecimal("confidence"),
                rs.getBigDecimal("materiality"),
                rs.getString("horizon"),
                rs.getBigDecimal("novelty"),
                rs.getBoolean("contradiction"),
                rs.getBoolean("ambiguity"),
                List.copyOf(symbols),
                rs.getString("evidence"),
                List.copyOf(risks),
                (Integer) rs.getObject("input_tokens"),
                (Integer) rs.getObject("output_tokens"),
                (Integer) rs.getObject("total_tokens"),
                rs.getInt("latency_ms"));
        }, Timestamp.from(fromInclusive), Timestamp.from(toExclusive), limit);
    }
}
