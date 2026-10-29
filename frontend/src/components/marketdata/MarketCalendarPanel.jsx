import { Badge, EmptyState, ErrorState, Skeleton } from '../ui.jsx';

export default function MarketCalendarPanel({ query }) {
  if (query.isPending) return <Skeleton className="h-32 w-full" />;
  if (query.isError) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const sessions = query.data?.sessions || [];
  if (!sessions.length) return <EmptyState title="No calendar sessions" description="Choose a valid date range to inspect expected market sessions." />;

  return (
    <div className="overflow-x-auto rounded-md border border-border">
      <table className="w-full min-w-[620px] text-left text-xs">
        <thead className="bg-surface-2/70 text-[11px] uppercase tracking-wide text-muted">
          <tr><th className="px-3 py-2 font-medium">Date</th><th className="px-3 py-2 font-medium">State</th><th className="px-3 py-2 font-medium">Open</th><th className="px-3 py-2 font-medium">Close</th></tr>
        </thead>
        <tbody>
          {sessions.map((session) => (
            <tr key={session.tradeDate} className="border-t border-border/70">
              <td className="px-3 py-2 text-text">{session.tradeDate}</td>
              <td className="px-3 py-2"><Badge tone={session.tradingDay ? 'gain' : 'neutral'}>{session.tradingDay ? 'TRADING' : 'CLOSED'}</Badge></td>
              <td className="px-3 py-2 text-muted">{session.open || '—'}</td>
              <td className="px-3 py-2 text-muted">{session.close || '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
