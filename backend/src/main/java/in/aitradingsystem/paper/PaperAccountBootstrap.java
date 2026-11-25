package in.aitradingsystem.paper;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.config.RiskProperties;
import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the single paper account on first start. Restarts reuse the persisted account. */
@Component
public class PaperAccountBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PaperAccountBootstrap.class);

    private final JdbcClient jdbc;
    private final RiskProperties props;
    private final AuditService audit;

    public PaperAccountBootstrap(JdbcClient jdbc, RiskProperties props, AuditService audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        BigDecimal capital = props.defaultCapital();
        int inserted = jdbc.sql("""
                INSERT INTO paper_account (id, starting_capital, cash_balance)
                VALUES (1, :capital, :capital) ON CONFLICT (id) DO NOTHING
                """).param("capital", capital).update();
        if (inserted == 1) {
            jdbc.sql("""
                    INSERT INTO paper_cash_entry (order_id, entry_type, amount, balance_after)
                    VALUES (NULL, 'CAPITAL_SEED', :capital, :capital)
                    """).param("capital", capital).update();
            audit.record("PAPER_ACCOUNT_CREATED", "PAPER_ACCOUNT", "1", Map.of("startingCapital", capital));
            log.info("Created paper account with starting capital {}", capital);
            return;
        }
        BigDecimal stored = jdbc.sql("SELECT starting_capital FROM paper_account WHERE id = 1")
            .query(BigDecimal.class).single();
        if (stored.compareTo(capital) != 0) {
            log.warn("Configured paper capital {} differs from persisted starting capital {}; persisted value is kept",
                capital, stored);
        }
    }
}