import { useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { API } from '../../lib/api.js';
import { formatDateTime } from '../../lib/format.js';
import { Badge, Button, EmptyState, Panel } from '../ui.jsx';

/** Separate provenance-aware RSS feed; its bearer token is held in component memory only. */
export function ProvenanceNewsPanel() {
  const [token, setToken] = useState('');
  const [items, setItems] = useState([]);
  const [message, setMessage] = useState('');
  const [loading, setLoading] = useState(false);

  const load = async (event) => {
    event?.preventDefault();
    if (!token.trim()) return;
    setLoading(true);
    setMessage('');
    try {
      const response = await fetch(`${API}/news?limit=20&offset=0`, {
        headers: { Authorization: `Bearer ${token.trim()}`, Accept: 'application/json' },
      });
      if (response.status === 401) {
        throw new Error('The news-feed token was rejected. Check NEWS_API_TOKEN on the backend.');
      }
      if (!response.ok) throw new Error(`Unable to load news (HTTP ${response.status}).`);
      const page = await response.json();
      setItems(Array.isArray(page.items) ? page.items : []);
    } catch (error) {
      setMessage(error?.message || 'Unable to load news from the backend.');
      setItems([]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Panel title="Provenance-aware RSS feed" subtitle="Source-linked articles and optional summaries; never trade instructions">
      <form className="mb-3 flex flex-col gap-2 sm:flex-row" onSubmit={load}>
        <label className="sr-only" htmlFor="news-feed-token">News feed bearer token</label>
        <input
          id="news-feed-token"
          type="password"
          autoComplete="off"
          spellCheck={false}
          value={token}
          onChange={(event) => setToken(event.target.value)}
          placeholder="News feed token"
          className="h-8 min-w-0 flex-1 rounded-md border border-border bg-surface-2 px-2.5 text-xs text-text placeholder:text-muted focus:border-accent focus:outline-none"
        />
        <Button type="submit" size="sm" icon={RefreshCw} loading={loading} disabled={!token.trim()}>
          {loading ? 'Loading' : 'Load feed'}
        </Button>
      </form>
      {message && <p role="alert" className="mb-3 text-xs text-loss">{message}</p>}
      {items.length === 0 ? (
        <EmptyState
          title="No RSS articles loaded"
          description="Enter the separately configured NEWS_API_TOKEN to load the bounded, read-only feed. This token is not saved to browser storage."
        />
      ) : (
        <ul className="flex flex-col">
          {items.map((item) => (
            <li key={item.id} className="flex flex-col gap-2 border-t border-border py-3 first:border-t-0 first:pt-0">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <a href={item.sourceUrl} target="_blank" rel="noopener noreferrer" className="text-sm font-medium leading-snug text-text hover:underline">
                  {item.title}
                </a>
                <Badge>{item.publisher || item.source}</Badge>
              </div>
              <div className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted">
                <span>Published {item.publishedAt ? formatDateTime(item.publishedAt) : 'time unavailable'}</span>
                <span>Fetched {item.fetchedAt ? formatDateTime(item.fetchedAt) : 'time unavailable'}</span>
                {(item.linkedSymbols || []).map((symbol) => <Badge key={symbol} tone="accent">{symbol}</Badge>)}
              </div>
              {item.summary && <p className="text-xs leading-relaxed text-muted">{item.summary}</p>}
              <p className="text-[10px] text-muted">
                {item.summaryProvider
                  ? `Summary: ${item.summaryProvider} · ${item.summaryModel || 'model unavailable'} · ${item.summaryGeneratedAt ? formatDateTime(item.summaryGeneratedAt) : 'time unavailable'}`
                  : item.ingestionStatus || 'INGESTED'}
              </p>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
