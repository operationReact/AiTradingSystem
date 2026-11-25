export default function StrategySelector({ strategies = [], value, onChange }) {
  return (
    <label className="grid gap-1 text-xs text-muted">
      Strategy catalogue
      <select value={value} onChange={(e) => onChange?.(e.target.value)} className="h-10 rounded-md border border-border bg-surface-2 px-3 text-sm text-text">
        <option value="ALL">All enabled strategies</option>
        {strategies.map((strategy) => <option key={strategy.code} value={strategy.code}>{strategy.code} · {strategy.version}</option>)}
      </select>
    </label>
  );
}
