import { Download, Search } from 'lucide-react';
import { Badge, Button, EmptyState, ErrorState, Notice, Panel, Skeleton } from '../ui.jsx';
import { formatBusinessDate, formatDateTime, formatInteger, formatInr } from '../../lib/format.js';

const EXCHANGES = [
  { value: '', label: 'All exchanges' },
  { value: 'NSE', label: 'NSE cash' },
  { value: 'BSE', label: 'BSE cash' },
  { value: 'NFO', label: 'NSE derivatives' },
  { value: 'BFO', label: 'BSE derivatives' },
  { value: 'MCX', label: 'MCX derivatives' },
  { value: 'CDS', label: 'Currency derivatives (CDS)' },
  { value: 'BCD', label: 'Currency derivatives (BCD)' },
];
const TYPES = [
  { value: '', label: 'All types' },
  { value: 'EQ', label: 'Equity (EQ)' },
  { value: 'FUTIDX', label: 'Index futures' },
  { value: 'FUTSTK', label: 'Stock futures' },
  { value: 'OPTIDX', label: 'Index options' },
  { value: 'OPTSTK', label: 'Stock options' },
  { value: 'FUTCOM', label: 'Commodity futures' },
  { value: 'OPTFUT', label: 'Commodity options' },
];
const inputClass = 'h-9 w-full rounded-md border border-border bg-surface-2 px-3 text-sm text-text placeholder:text-muted focus:border-accent focus:outline-none';
const selectClass = 'h-9 w-full rounded-md border border-border bg-surface-2 px-2 text-sm text-text focus:border-accent focus:outline-none';

function price(value) {
  return value === null || value === undefined || value === '' ? '—' : formatInr(value);
}

function ImportTone({ status }) {
  const tone = status === 'SUCCEEDED' ? 'gain' : status === 'FAILED' ? 'loss' : 'neutral';
  return <Badge tone={tone}>{status || 'UNKNOWN'}</Badge>;
}

export default function InstrumentMasterPanel({ controller }) {
  const { connected, draftQuery, setDraftQuery, draftExchange, setDraftExchange, draftType, setDraftType, criteria, page,
    setPage, pageSize, status, search, imports, importMaster, catalogue, latestAttempt, submitSearch, clearSearch, onImport } = controller;
  return (
    <div className="flex flex-col gap-4">
      <Panel
        title="Active instrument catalogue"
        subtitle="Versioned Angel One instrument master · reference data, not live prices"
        actions={
          <Button
            variant="primary"
            size="sm"
            icon={Download}
            loading={importMaster.isPending}
            disabled={!connected || importMaster.isPending}
            onClick={onImport}
          >
            Import latest master
          </Button>
        }
      >
        {!connected && (
          <Notice tone="neutral" title="Operator key required">
            Connect the operator API key in the top bar to search the catalogue or trigger an import.
          </Notice>
        )}
        {connected && status.isPending && <Skeleton className="h-16 w-full" />}
        {status.isError && <ErrorState error={status.error} onRetry={() => status.refetch()} />}
        {catalogue && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <div className="rounded-md border border-border bg-surface-2/40 p-3">
              <p className="text-[11px] uppercase tracking-wide text-muted">Active instruments</p>
              <p className="mt-1 text-lg font-semibold text-text">{formatInteger(catalogue.activeCount)}</p>
            </div>
            <div className="rounded-md border border-border bg-surface-2/40 p-3">
              <p className="text-[11px] uppercase tracking-wide text-muted">Catalogue version</p>
              <p className="mt-1 truncate font-mono text-xs text-text" title={catalogue.version || ''}>{catalogue.version || 'Not imported yet'}</p>
            </div>
            <div className="rounded-md border border-border bg-surface-2/40 p-3">
              <p className="text-[11px] uppercase tracking-wide text-muted">Activated</p>
              <p className="mt-1 text-sm font-medium text-text">{catalogue.activatedAt ? formatDateTime(catalogue.activatedAt) : '—'}</p>
            </div>
          </div>
        )}
        <div className="mt-3 text-xs text-muted">
          The import is an explicit backend operation. A malformed, oversized, incomplete or conflicting source dump is rejected without replacing the current catalogue.
        </div>
        {importMaster.isError && (
          <div className="mt-3"><Notice tone="loss" title="Import request failed">{importMaster.error?.message || 'The backend could not start the import.'}</Notice></div>
        )}
        {latestAttempt && (
          <div className="mt-3">
            {latestAttempt.status === 'SUCCEEDED' ? (
              <Notice tone="neutral" title="Instrument master imported">
                Published {formatInteger(latestAttempt.activeCount)} instruments. Parsed {formatInteger(latestAttempt.parsedCount)} rows; rejected {formatInteger(latestAttempt.rejectedCount)} and de-duplicated {formatInteger(latestAttempt.duplicateCount)}.
              </Notice>
            ) : (
              <Notice tone="loss" title="Instrument master was not published">
                {latestAttempt.errorMessage || latestAttempt.errorCode || 'Import failed'}. The prior active catalogue remains in place.
              </Notice>
            )}
          </div>
        )}
      </Panel>

      <Panel title="Search instruments" subtitle="Search by symbol prefix or instrument name; results are served from the active version">
        <form onSubmit={submitSearch} className="grid grid-cols-1 gap-3 md:grid-cols-[minmax(15rem,2fr)_1fr_1fr_auto_auto] md:items-end">
          <div>
            <label htmlFor="instrument-query" className="mb-1 block text-xs font-medium text-muted">Symbol or name</label>
            <input
              id="instrument-query"
              className={inputClass}
              maxLength={100}
              pattern="[A-Za-z0-9 .&/_-]*"
              value={draftQuery}
              onChange={(event) => setDraftQuery(event.target.value)}
              placeholder="RELIANCE, SBIN, NIFTY…"
              autoComplete="off"
            />
          </div>
          <div>
            <label htmlFor="instrument-exchange" className="mb-1 block text-xs font-medium text-muted">Exchange</label>
            <select id="instrument-exchange" className={selectClass} value={draftExchange} onChange={(e) => setDraftExchange(e.target.value)}>
              {EXCHANGES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="instrument-type" className="mb-1 block text-xs font-medium text-muted">Instrument type</label>
            <select id="instrument-type" className={selectClass} value={draftType} onChange={(e) => setDraftType(e.target.value)}>
              {TYPES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
            </select>
          </div>
          <Button type="submit" variant="primary" icon={Search} disabled={!connected}>Search</Button>
          <Button type="button" variant="secondary" onClick={clearSearch} disabled={!connected}>Clear</Button>
        </form>

        <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
          <p className="text-xs text-muted">
            {search.data ? formatInteger(search.data.totalElements) + ' matches' : 'Search results'}
            {search.isFetching && <span> · Updating…</span>}
          </p>
          {criteria.q && <Badge tone="accent">{criteria.q}</Badge>}
        </div>

        {connected && search.isPending && <Skeleton className="mt-2 h-48 w-full" />}
        {search.isError && <div className="mt-2"><ErrorState error={search.error} onRetry={() => search.refetch()} /></div>}
        {search.data && search.data.content.length === 0 && (
          <div className="mt-2">
            <EmptyState
              title={catalogue?.activeCount ? 'No matching instruments' : 'No active catalogue yet'}
              description={catalogue?.activeCount ? 'Try a shorter symbol prefix or remove an exchange/type filter.' : 'Import the latest Angel One instrument master to enable verified symbol search.'}
              action={!catalogue?.activeCount && connected ? <Button size="sm" variant="secondary" onClick={onImport} loading={importMaster.isPending}>Import instrument master</Button> : undefined}
            />
          </div>
        )}
        {search.data && search.data.content.length > 0 && (
          <>
            <div className="mt-2 overflow-x-auto rounded-md border border-border">
              <table className="min-w-[940px] w-full text-left text-xs">
                <thead className="bg-surface-2/70 text-[11px] uppercase tracking-wide text-muted">
                  <tr>
                    <th className="px-3 py-2 font-medium">Instrument</th>
                    <th className="px-3 py-2 font-medium">Exchange</th>
                    <th className="px-3 py-2 font-medium">Token</th>
                    <th className="px-3 py-2 font-medium">Type</th>
                    <th className="px-3 py-2 font-medium">Expiry</th>
                    <th className="px-3 py-2 text-right font-medium">Strike</th>
                    <th className="px-3 py-2 text-right font-medium">Lot</th>
                    <th className="px-3 py-2 text-right font-medium">Tick size</th>
                  </tr>
                </thead>
                <tbody>
                  {search.data.content.map((item) => (
                    <tr key={item.id} className="border-t border-border/70 hover:bg-surface-2/40">
                      <td className="px-3 py-2">
                        <p className="font-medium text-text">{item.tradingSymbol}</p>
                        <p className="mt-0.5 max-w-[18rem] truncate text-muted">{item.name || '—'}</p>
                      </td>
                      <td className="px-3 py-2">
                        <p className="text-text">{item.exchange}</p>
                        <p className="text-muted">{item.exchangeSegment}</p>
                      </td>
                      <td className="px-3 py-2 font-mono text-muted">{item.symbolToken}</td>
                      <td className="px-3 py-2 text-text">{item.instrumentType || '—'}</td>
                      <td className="px-3 py-2 text-text">{item.expiry ? formatBusinessDate(item.expiry) : '—'}</td>
                      <td className="num px-3 py-2 text-right text-text">{item.strike === null ? '—' : price(item.strike)}</td>
                      <td className="num px-3 py-2 text-right text-text">{formatInteger(item.lotSize)}</td>
                      <td className="num px-3 py-2 text-right text-text">{price(item.tickSize)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-3 flex items-center justify-between gap-3 text-xs text-muted">
              <span>Page {search.data.page + 1} of {Math.max(search.data.totalPages, 1)}</span>
              <div className="flex gap-2">
                <Button size="sm" variant="secondary" disabled={page === 0 || search.isFetching} onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous</Button>
                <Button size="sm" variant="secondary" disabled={!search.data.hasNext || search.isFetching} onClick={() => setPage((p) => p + 1)}>Next</Button>
              </div>
            </div>
          </>
        )}
      </Panel>

      <Panel title="Recent import attempts" subtitle="Failures remain visible for troubleshooting">
        {connected && imports.isPending && <Skeleton className="h-24 w-full" />}
        {imports.isError && <ErrorState error={imports.error} onRetry={() => imports.refetch()} />}
        {imports.data && imports.data.length === 0 && <EmptyState title="No import attempts yet" description="Import the catalogue to record its first version." />}
        {imports.data && imports.data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[620px] text-left text-xs">
              <thead className="text-[11px] uppercase tracking-wide text-muted">
                <tr>
                  <th className="px-2 py-2 font-medium">Started</th>
                  <th className="px-2 py-2 font-medium">Status</th>
                  <th className="px-2 py-2 text-right font-medium">Parsed</th>
                  <th className="px-2 py-2 text-right font-medium">Published</th>
                  <th className="px-2 py-2 font-medium">Result</th>
                </tr>
              </thead>
              <tbody>
                {imports.data.map((run) => (
                  <tr key={run.id} className="border-t border-border/70">
                    <td className="px-2 py-2 text-muted">{formatDateTime(run.startedAt)}</td>
                    <td className="px-2 py-2"><ImportTone status={run.status} /></td>
                    <td className="num px-2 py-2 text-right text-text">{formatInteger(run.parsedCount)}</td>
                    <td className="num px-2 py-2 text-right text-text">{formatInteger(run.activeCount)}</td>
                    <td className="max-w-[20rem] truncate px-2 py-2 text-muted" title={run.errorMessage || ''}>{run.errorCode || run.errorMessage || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
    </div>
  );
}
