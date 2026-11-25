package in.aitradingsystem.backtest;

import in.aitradingsystem.scanner.ScanCandidate;
import in.aitradingsystem.scanner.ScanContext;
import in.aitradingsystem.scanner.ScannerStrategy;
import java.util.Optional;

/** Reuses deterministic scanner strategy math for backtests without duplicating strategy logic. */
public final class ScannerBacktestAdapter implements BacktestStrategy {
    private final ScannerStrategy delegate;

    public ScannerBacktestAdapter(ScannerStrategy delegate) {
        if (delegate == null) throw new IllegalArgumentException("delegate strategy is required");
        this.delegate = delegate;
    }

    @Override
    public String code() {
        return delegate.descriptor().code();
    }

    @Override
    public String version() {
        return delegate.descriptor().version();
    }

    @Override
    public BacktestSignal evaluate(BacktestContext context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        ScanContext scanContext = new ScanContext(
                context.instrumentId(), context.tradingSymbol(), context.exchange(), context.interval(),
                context.cutoff(), context.candles());
        Optional<ScanCandidate> candidate = delegate.evaluate(scanContext);
        if (candidate.isEmpty()) return BacktestSignal.none();
        ScanCandidate value = candidate.get();
        return new BacktestSignal(true, value.referencePrice(), value.suggestedStop(), value.suggestedTarget(),
                value.candleTime(), value.reasons());
    }
}
