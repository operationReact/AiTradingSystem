import { Badge, Button, EmptyState, Notice, Panel } from './ui.jsx';
const MODES = ['MANUAL', 'AUTO', 'HYBRID'];

export default function ApprovalPolicySettings({ controller, clear = false }) {
  const { connected, policies, update, onUpdateMode } = controller;
  return (
    <Panel
      title="Approval policy settings"
      subtitle={clear
        ? 'Choose when an order needs a person to review it. Hard risk rules always remain authoritative.'
        : 'Operator approval mode by execution policy. This never overrides deterministic hard-risk rejection.'}
    >
      {!connected ? (
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
                <p className="text-xs text-muted">{policy.strategy ? `Strategy ${policy.strategy}` : 'Default policy'} · Effective {policy.effectiveFrom ? new Date(policy.effectiveFrom).toLocaleString() : '—'}</p>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                {MODES.map((mode) => (
                  <Button
                    key={mode}
                    size="sm"
                    variant={policy.approvalMode === mode ? 'primary' : 'secondary'}
                    disabled={update.isPending || !policy.active || policy.approvalMode === mode}
                    onClick={() => onUpdateMode(policy, mode)}
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
