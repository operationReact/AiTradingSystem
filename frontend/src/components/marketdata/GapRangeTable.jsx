import { EmptyState } from '../ui.jsx';
import { formatDateTime } from '../../lib/format.js';

export default function GapRangeTable({ gaps = [], compact = false }) {
  if (!gaps.length) {
    return <EmptyState title="No candle gaps detected" description="The evaluated window has no missing expected candle slots." />;
  }

  return (
    <div className="overflow-x-auto rounded-md border border-border">
      <table className="w-full min-w-[520px] text-left text-xs">
        <thead className="bg-surface-2/70 text-[11px] uppercase tracking-wide text-muted">
          <tr>
            <th className="px-3 py-2 font-medium">#</th>
            <th className="px-3 py-2 font-medium">Gap start (IST)</th>
            <th className="px-3 py-2 font-medium">Gap end (IST)</th>
          </tr>
        </thead>
        <tbody>
          {gaps.slice(0, compact ? 8 : 100).map((gap, index) => (
            <tr key={`${gap.from}-${gap.to}-${index}`} className="border-t border-border/70">
              <td className="px-3 py-2 text-muted">{index + 1}</td>
              <td className="px-3 py-2 text-text">{formatDateTime(gap.from)}</td>
              <td className="px-3 py-2 text-text">{formatDateTime(gap.to)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {compact && gaps.length > 8 && <p className="border-t border-border px-3 py-2 text-xs text-muted">+{gaps.length - 8} additional gap ranges</p>}
    </div>
  );
}
