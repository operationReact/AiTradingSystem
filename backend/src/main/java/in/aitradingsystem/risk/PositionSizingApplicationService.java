package in.aitradingsystem.risk;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.instrument.InstrumentMasterRepository;
import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads authoritative portfolio/policy/instrument state, then delegates to the deterministic calculator. */
@Service
public class PositionSizingApplicationService {
    private final JdbcClient jdbc;
    private final InstrumentMasterRepository instruments;
    private final RiskPolicyConfigRepository policies;
    private final PositionSizingService sizing;
    private final RiskMetrics metrics;

    public PositionSizingApplicationService(
            JdbcClient jdbc,
            InstrumentMasterRepository instruments,
            RiskPolicyConfigRepository policies,
            PositionSizingService sizing,
            RiskMetrics metrics) {
        this.jdbc = jdbc;
        this.instruments = instruments;
        this.policies = policies;
        this.sizing = sizing;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public PositionSizeSuggestion suggest(PositionSizeRequest request) {
        try {
            Instrument instrument = requireEligibleInstrument(request);
            Account account = jdbc.sql("SELECT cash_balance FROM paper_account WHERE id = 1")
                    .query((rs, row) -> new Account(rs.getBigDecimal("cash_balance")))
                    .optional().orElseThrow(() -> new IllegalStateException("Paper account is not initialized"));
            BigDecimal grossExposure = valueOrZero(jdbc.sql("SELECT COALESCE(SUM(cost_basis), 0) FROM paper_position")
                    .query(BigDecimal.class).single());
            BigDecimal symbolExposure = valueOrZero(jdbc.sql(
                            "SELECT COALESCE(cost_basis, 0) FROM paper_position WHERE symbol = :symbol")
                    .param("symbol", request.symbol())
                    .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO));
            BigDecimal currentEquity = account.cash().add(grossExposure);

            PortfolioRiskState state = new PortfolioRiskState(
                    currentEquity,
                    account.cash(),
                    grossExposure,
                    symbolExposure,
                    Math.max(1, instrument.lotSize()));
            PositionSizeSuggestion suggestion = sizing.suggest(request, policies.requireActive(), state);
            metrics.recordPositionSize("SUCCESS");
            return suggestion;
        } catch (RuntimeException ex) {
            metrics.recordPositionSize("REJECTED");
            throw ex;
        }
    }

    private Instrument requireEligibleInstrument(PositionSizeRequest request) {
        final Instrument instrument;
        try {
            instrument = instruments.findActiveInstrumentById(request.instrumentId());
        } catch (InstrumentMasterException ex) {
            throw new IllegalArgumentException("instrumentId is not present in the active catalogue", ex);
        }
        if (!instrument.exchange().equalsIgnoreCase(request.exchange())) {
            throw new IllegalArgumentException("exchange does not match the active instrument");
        }
        if (!instrument.tradingSymbol().equalsIgnoreCase(request.symbol())) {
            throw new IllegalArgumentException("symbol does not match the active instrument");
        }
        if (!"EQ".equalsIgnoreCase(instrument.instrumentType())) {
            throw new IllegalArgumentException("position sizing currently supports cash-equity instruments only");
        }
        return instrument;
    }

    private static BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.max(BigDecimal.ZERO);
    }

    private record Account(BigDecimal cash) {}
}
