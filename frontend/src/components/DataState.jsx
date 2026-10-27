import { ErrorState, Notice, Skeleton } from './ui.jsx';
import { useApp } from '../context/AppContext.jsx';

/**
 * Renders the shared request states: no operator key, first load, hard error, and
 * stale data (a refresh failed but previous data is still shown).
 */
export function DataState({ query, skeleton, children, emptyWhen, emptyFallback }) {
  const { apiKey } = useApp();

  if (!apiKey) {
    return (
      <Notice tone="neutral" title="Operator key required">
        Enter the operator API key in the top bar to load live paper-trading data. The key is kept in memory only.
      </Notice>
    );
  }
  if (query.isPending) return skeleton ?? <Skeleton className="h-40 w-full" />;
  if (query.isError && !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;

  const stale = query.isError && query.data;
  return (
    <div className="flex flex-col gap-3">
      {stale && (
        <Notice tone="warn" title="Showing last successful data" action={<button type="button" className="text-xs underline" onClick={() => query.refetch()}>Retry</button>}>
          The latest refresh failed: {query.error?.message}
        </Notice>
      )}
      {emptyWhen && emptyWhen(query.data) ? emptyFallback : children(query.data)}
    </div>
  );
}
