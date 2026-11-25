import { Badge } from '../ui.jsx';

export default function ProviderHealthBadge({ status, isLoading, error }) {
  if (isLoading) return <Badge tone="neutral">PROVIDER CHECKING</Badge>;
  if (error) return <Badge tone="loss">PROVIDER UNKNOWN</Badge>;
  const state = status?.state || 'UNKNOWN';
  const tone = state === 'AUTHENTICATED' ? 'gain'
    : state === 'DEGRADED' || state === 'CONFIGURED_UNVERIFIED' ? 'warn'
      : state === 'NOT_CONFIGURED' || state === 'SESSION_EXPIRED' || state === 'UNAVAILABLE' ? 'loss'
        : 'neutral';
  return <Badge tone={tone}>ANGEL ONE · {state}</Badge>;
}
