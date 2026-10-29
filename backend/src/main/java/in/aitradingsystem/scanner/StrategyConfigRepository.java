package in.aitradingsystem.scanner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StrategyConfigRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public StrategyConfigRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void lockStrategy(String strategyCode) {
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> { rs.getObject(1); }, strategyCode);
    }

    public int nextVersion(String strategyCode) {
        Integer version = jdbc.queryForObject(
                "select coalesce(max(config_version), 0) + 1 from strategy_config where strategy_code = ?",
                Integer.class, strategyCode);
        return version == null ? 1 : version;
    }

    public Optional<StrategyConfigSnapshot> findActive(String strategyCode) {
        List<StrategyConfigSnapshot> rows = jdbc.query(
                "select * from strategy_config where strategy_code = ? and effective_to is null order by config_version desc limit 1",
                (rs, rowNum) -> map(rs.getObject("id", UUID.class), rs.getString("strategy_code"),
                        rs.getInt("config_version"), rs.getBoolean("enabled"), rs.getString("config_json"),
                        rs.getTimestamp("effective_from").toInstant(), null, rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant()), strategyCode);
        return rows.stream().findFirst();
    }

    public void closeActive(String strategyCode, Instant effectiveTo) {
        jdbc.update("update strategy_config set effective_to = ? where strategy_code = ? and effective_to is null",
                Timestamp.from(effectiveTo), strategyCode);
    }

    public StrategyConfigSnapshot insert(String strategyCode, int version, boolean enabled, JsonNode config,
                                         Instant effectiveFrom, String createdBy) {
        UUID id = UUID.randomUUID();
        String json = write(config);
        Instant createdAt = Instant.now();
        jdbc.update("insert into strategy_config (id, strategy_code, config_version, enabled, config_json, effective_from, created_by, created_at) " +
                        "values (?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)",
                id, strategyCode, version, enabled, json, Timestamp.from(effectiveFrom), createdBy, Timestamp.from(createdAt));
        return new StrategyConfigSnapshot(id, strategyCode, version, enabled, config.deepCopy(), effectiveFrom, null,
                createdBy, createdAt);
    }

    public List<StrategyConfigSnapshot> history(String strategyCode) {
        return jdbc.query("select * from strategy_config where strategy_code = ? order by config_version desc",
                (rs, rowNum) -> {
                    Timestamp to = rs.getTimestamp("effective_to");
                    return map(rs.getObject("id", UUID.class), rs.getString("strategy_code"),
                            rs.getInt("config_version"), rs.getBoolean("enabled"), rs.getString("config_json"),
                            rs.getTimestamp("effective_from").toInstant(), to == null ? null : to.toInstant(),
                            rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());
                }, strategyCode);
    }

    private StrategyConfigSnapshot map(UUID id, String code, int version, boolean enabled, String configJson,
                                       Instant from, Instant to, String createdBy, Instant createdAt) {
        try {
            return new StrategyConfigSnapshot(id, code, version, enabled, objectMapper.readTree(configJson),
                    from, to, createdBy, createdAt);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid persisted strategy config for " + code, e);
        }
    }

    private String write(JsonNode config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("strategy config must be JSON serializable", e);
        }
    }
}
