import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../lib/api.js';
import { useApp } from '../context/AppContext.jsx';
import { Badge, Button, EmptyState, Notice, Panel } from './ui.jsx';

const MODES = ['MANUAL', 'AUTO', 'HYBRID'];

export default function ApprovalPolicySettings() {
  const { apiKey, experience } = useApp();
  const qc = useQueryClient();
  const policies = useQuery({
    queryKey: ['approvals', 'policies', apiKey ? 'authed' : 'anon'],
    queryFn: ({ signal }) => apiFetch('/approval-policies', { apiKey, signal }),
    enabled: !!apiKey,
    staleTime: 15_000,
  });
  const update = useMutation({
    mutationFn: ({ id, approvalMode }) => apiFetch(`/approval-policies/${id}`, {
      apiKey,
      method: 'PUT',
      body: { approvalMode },
    }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['approvals', 'policies'] }),
  });

  const clear = experience === 'clear-invest';

  return (
    <Panel
      title="Approval policy settings"
      subtitle={clear
        ? 'Choose when an order needs a person to review it. Hard risk rules always remain authoritative.'
        : 'Operator approval mode by execution policy. This never overrides deterministic hard-risk rejection.'}
    >
      {!apiKey ? (
        <Notice title="Operator key required">Connect an operator key to view or change approval policies.</Notice>
      ) : policies.isPending ? (
        <div className="h-28 animate-pulse rounded bg-surface-2" />
      ) : policies.isError ? (
        <Notice tone="loss" title="Could not load approval policies">{policies.error?.message}</Notice>
      ) : !policies.data?.length ? (
        <EmptyState title="No execution policies" description="Create an execution policy before configuring approval behavior." />
      ) : (
        <div className="space-y-3">
          {policies.data.map((policy) => (
            <div key={policy.id} className="flex flex-col gap-3 rounded-lg border border-border bg-surface-2/40 p-3 lg:flex-row lg:items-center lg:justify-between">
              <div className="min-w-0 space-y-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium text-text">Policy v{policy.policyVersion}</span>
                  <Badge tone={policy.active ? 'gain' : 'neutral'}>{policy.active ? 'Active' : 'Inactive'}</Badge>
                  <Badge tone="accent">{policy.executionMode}</Badge>
                  <Badge>{policy.tradingStyle}</Badge>
                </div>
                <p className="text-xs text-muted">{policy.strategyId ? `Strategy ${String(policy.strategyId).slice(0, 8)}` : 'Default policy'} · Effective {policy.effectiveFrom ? new Date(policy.effectiveFrom).toLocaleString() : '—'}</p>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                {MODES.map((mode) => (
                  <Button
                    key={mode}
                    size="sm"
                    variant={policy.approvalMode === mode ? 'primary' : 'secondary'}
                    disabled={update.isPending || policy.approvalMode === mode}
                    onClick={() => update.mutate({ id: policy.id, approvalMode: mode })}
                  >
                    {mode}
                  </Button>
                ))}
              </div>
            </div>
          ))}
          {update.isError && <Notice tone="loss" title="Policy update failed">{update.error?.message}</Notice>}
          <Notice tone="neutral" title="Safety boundary">
            Approval mode affects execution authorization only. Emergency stop, market-session checks, freshness checks and hard-risk limits are revalidated immediately before execution.
          </Notice>
        </div>
      )}
    </Panel>
  );
}
