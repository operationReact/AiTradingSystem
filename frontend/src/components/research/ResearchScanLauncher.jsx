import { Button } from '../ui.jsx';
import StrategySelector from './StrategySelector.jsx';

const INTERVALS = ['ONE_MINUTE', 'THREE_MINUTE', 'FIVE_MINUTE', 'TEN_MINUTE', 'FIFTEEN_MINUTE', 'THIRTY_MINUTE', 'ONE_HOUR'];

export default function ResearchScanLauncher({ strategies, value, onChange, onStart, pending, connected }) {
  const { strategy, interval, limit } = value;

  return (
    <section className="grid gap-4 rounded-lg border border-border bg-surface p-4 md:grid-cols-[1fr_220px_160px_auto] md:items-end">
      <StrategySelector strategies={strategies} value={strategy} onChange={strategy => onChange({ ...value, strategy })} />
      <label className="grid gap-1 text-xs text-muted">Interval
        <select aria-label="Interval" value={interval} onChange={(e) => onChange({ ...value, interval: e.target.value })} className="h-10 rounded-md border border-border bg-surface-2 px-3 text-sm text-text">
          {INTERVALS.map((item) => <option key={item}>{item}</option>)}
        </select>
      </label>
      <label className="grid gap-1 text-xs text-muted">Universe limit
        <input type="number" min="1" max="500" value={limit} onChange={(e) => onChange({ ...value, limit: Math.max(1, Math.min(500, Number(e.target.value) || 1)) })} className="h-10 rounded-md border border-border bg-surface-2 px-3 text-sm text-text" />
      </label>
      <Button variant="primary" disabled={pending || !connected} onClick={() => onStart?.({ interval, limit, strategy })}>{pending ? 'Starting…' : 'Start scan'}</Button>
      {strategy !== 'ALL' && <p className="text-xs text-muted md:col-span-4">The current backend scan runs the enabled registry as a set; this selector is informational until server-side per-strategy scan filtering is introduced.</p>}
    </section>
  );
}
