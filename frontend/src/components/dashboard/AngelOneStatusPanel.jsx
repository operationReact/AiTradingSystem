import { RefreshCw } from 'lucide-react';
import { Badge, Button, ErrorState, Notice, Panel, Skeleton } from '../ui.jsx';
import { formatDateTime } from '../../lib/format.js';

const STATES = {
  NOT_CONFIGURED: { label: 'Not configured', tone: 'neutral', note: 'Set the ANGELONE_* environment variables on the backend. No provider session has been verified.' },
  CONFIGURED_UNVERIFIED: { label: 'Configured, not verified', tone: 'warn', note: 'Credentials are present. Run a connection test to verify them.' },
  AUTHENTICATED: { label: 'Authenticated', tone: 'gain', note: 'Session is active on the server.' },
  SESSION_EXPIRED: { label: 'Session expired', tone: 'warn', note: 'The last provider request reported an expired session. Recovery is attempted only for that response.' },
  DEGRADED: { label: 'Degraded', tone: 'warn', note: 'Recent requests failed or were throttled. Market data may be stale.' },
  UNAVAILABLE: { label: 'Unavailable', tone: 'loss', note: 'Angel One could not be reached. Market data is not live.' },
};

export function AngelOneStatusPanel({ controller }) {
  const { status, test, connected, onTest } = controller;

  const actions = (
    <Button size="sm" variant="secondary" icon={RefreshCw} loading={test.isPending}
      disabled={!connected || test.isPending} onClick={onTest}>
      Test connection
    </Button>
  );

  return (
    <Panel title="Angel One SmartAPI" subtitle="Primary broker and market-data provider" actions={actions}>
      {!connected && <Notice tone="neutral" title="Operator key required">Connect the operator key to inspect provider evidence.</Notice>}
      {connected && status.isPending && <Skeleton className="h-16 w-full" />}
      {status.isError && <ErrorState error={status.error} onRetry={() => status.refetch()} />}
      {status.data && <StatusBody health={status.data} />}
      {test.isError && (
        <Notice tone="loss" title="Connection test failed">
          {test.error?.message || 'Unknown error'}
        </Notice>
      )}
    </Panel>
  );
}

function StatusBody({ health }) {
  const info = STATES[health.state] ?? { label: health.state, tone: 'neutral', note: '' };
  const sessionAge = Number.isFinite(health.sessionAgeSeconds) ? `${health.sessionAgeSeconds}s` : 'unknown';
  return (
    <div className="space-y-3 text-xs">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={info.tone}>{info.label}</Badge>
        <span className="text-muted">{info.note}</span>
      </div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-muted">
        <dt>Session</dt>
        <dd className="text-text">{health.sessionActive ? `Active · age ${sessionAge}` : 'Inactive'}</dd>
        <dt>Last successful quote</dt>
        <dd className="text-text">{health.lastQuoteAt ? formatDateTime(health.lastQuoteAt) : 'never'}</dd>
        <dt>Last successful call</dt>
        <dd className="text-text">{health.lastSuccessAt ? formatDateTime(health.lastSuccessAt) : 'never'}</dd>
        <dt>Last error code</dt>
        <dd className="text-text">{health.lastErrorCode || 'none'}</dd>
        <dt>Checked</dt>
        <dd className="text-text">{health.checkedAt ? formatDateTime(health.checkedAt) : '—'}</dd>
      </dl>
    </div>
  );
}
