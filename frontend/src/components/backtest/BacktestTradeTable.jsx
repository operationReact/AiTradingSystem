import { EmptyState } from '../ui.jsx';
import { formatDateTime, formatInr, formatInteger, pnlTone } from '../../lib/format.js';

export default function BacktestTradeTable({ trades = [] }) {
  if (!trades.length) return <EmptyState title="No trades in this run" description="The strategy did not open a qualifying paper position in the selected history." />;
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[760px] text-left text-xs">
        <thead><tr className="border-b border-border text-[11px] uppercase tracking-wide text-muted">
          <th className="px-2 py-2 font-medium">#</th><th className="px-2 py-2 font-medium">Entry</th><th className="px-2 py-2 font-medium">Exit</th>
          <th className="px-2 py-2 text-right font-medium">Qty</th><th className="px-2 py-2 text-right font-medium">Entry</th><th className="px-2 py-2 text-right font-medium">Exit</th>
          <th className="px-2 py-2 text-right font-medium">Net P&amp;L</th><th className="px-2 py-2 font-medium">Reason</th>
        </tr></thead>
        <tbody>{trades.slice(0, 50).map((trade) => (
          <tr key={trade.tradeNumber} className="border-b border-border/60 last:border-0">
            <td className="px-2 py-2 num text-muted">{trade.tradeNumber}</td>
            <td className="px-2 py-2 text-text">{formatDateTime(trade.entryAt)}</td><td className="px-2 py-2 text-text">{formatDateTime(trade.exitAt)}</td>
            <td className="px-2 py-2 text-right num text-text">{formatInteger(trade.quantity)}</td><td className="px-2 py-2 text-right num text-text">{formatInr(trade.entryPrice)}</td>
            <td className="px-2 py-2 text-right num text-text">{formatInr(trade.exitPrice)}</td><td className={`px-2 py-2 text-right num font-semibold ${pnlTone(trade.netPnl)}`}>{formatInr(trade.netPnl)}</td>
            <td className="px-2 py-2 text-muted">{trade.exitReason || '—'}</td>
          </tr>
        ))}</tbody>
      </table>
      {trades.length > 50 && <p className="mt-2 text-xs text-muted">Showing first 50 of {trades.length} trades. Export CSV for the complete run.</p>}
    </div>
  );
}
