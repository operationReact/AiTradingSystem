import { Info } from 'lucide-react';
import { Button, Modal } from '../ui.jsx';

export default function AssumptionsDrawer({ open, onOpenChange, run }) {
  return (
    <Modal open={open} onOpenChange={onOpenChange} title="Backtest assumptions" description="What the simulator includes and what it cannot prove" size="lg">
      <div className="space-y-4 text-sm text-muted">
        <div className="flex gap-2 rounded-xl border border-border bg-surface-2/50 p-3"><Info className="mt-0.5 h-4 w-4 shrink-0 text-accent" /><p>This is a PAPER historical simulation using persisted point-in-time candles. It does not place broker orders and it is not a forecast or investment recommendation.</p></div>
        <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div><dt className="text-xs font-semibold text-text">Execution</dt><dd>Signals execute only on the next observed candle. Missing sessions are never fabricated.</dd></div>
          <div><dt className="text-xs font-semibold text-text">Leakage protection</dt><dd>Strategy history is bounded at each simulation index so future candles are not visible.</dd></div>
          <div><dt className="text-xs font-semibold text-text">Fees</dt><dd>{run?.feeRate != null ? `Modeled fee rate: ${run.feeRate}` : 'Modeled fees are deducted by the backend.'}</dd></div>
          <div><dt className="text-xs font-semibold text-text">Slippage</dt><dd>{run?.slippageRate != null ? `Modeled slippage rate: ${run.slippageRate}` : 'Modeled slippage is applied by the backend.'}</dd></div>
          <div><dt className="text-xs font-semibold text-text">Initial capital</dt><dd>{run?.initialCapital ?? 'Defined for each run'}; separate validation periods do not share account state.</dd></div>
          <div><dt className="text-xs font-semibold text-text">Data quality</dt><dd>Incomplete, duplicate, non-chronological, or quality-ineligible candle histories fail closed.</dd></div>
        </dl>
      </div>
      <div className="mt-5 flex justify-end"><Button variant="primary" onClick={() => onOpenChange(false)}>Understood</Button></div>
    </Modal>
  );
}
