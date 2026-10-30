import { ShieldCheck } from 'lucide-react';
import { Button, EmptyState, KpiCard, Notice, Panel, SegmentedControl } from '../../ui.jsx';
import ApprovalCard from './ApprovalCard.jsx';
const FILTERS = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'AUTO_APPROVED'];

export default function ApprovalWorkspace({ model, actions, clear = false }) {
  const { connected, totalPending, decisionResult, state, page, approvals } = model;
  const { onFilter, setPage, openDecision, onRetry } = actions;
  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <div className="flex items-center gap-2"><ShieldCheck className="h-5 w-5 text-accent" aria-hidden /><h1 className="text-xl font-semibold text-text">Execution approvals</h1></div>
          <p className="mt-1 text-sm text-muted">{clear ? 'Review orders that need a person before execution.' : 'Operator queue for policy-gated PAPER execution decisions.'}</p>
        </div>
        <KpiCard label="Pending approvals" value={totalPending} hint="Refreshes every 15 seconds" />
      </div>

      {!connected ? (
        <Notice title="Operator key required">Connect an operator key before reviewing approvals.</Notice>
      ) : (
        <>
          {decisionResult && (
            <Notice
              tone={decisionResult.order?.status === 'FILLED' ? 'neutral' : 'warn'}
              title={decisionResult.order?.status === 'FILLED' ? 'PAPER order filled'
                : decisionResult.order?.status === 'FAILED' ? 'Approval recorded; execution blocked'
                  : `Current approval state: ${decisionResult.approval.state}`}
            >
              {decisionResult.orderUnavailable ? 'The decision was recorded, but the order status could not be refreshed. Check order history before retrying.'
                : decisionResult.order?.rejectionReason || decisionResult.approval.decisionReason
                  || 'The server returned the current persisted decision.'}
            </Notice>
          )}
          <Panel title="Queue" subtitle="A decision is never permanent execution authorization; the backend revalidates immediately before execution.">
            <div className="mb-4 overflow-x-auto">
              <SegmentedControl
                label="Approval state filter"
                value={state}
                onChange={onFilter}
                options={FILTERS.map((value) => ({ value, label: value.replace('_', ' ') }))}
              />
            </div>
            {approvals.isPending && !approvals.data ? (
              <div className="h-52 animate-pulse rounded bg-surface-2" />
            ) : approvals.isError ? (
              <Notice tone="loss" title="Could not load approvals" action={<Button onClick={onRetry}>Retry</Button>}>{approvals.error?.message}</Notice>
            ) : !approvals.data?.items?.length ? (
              <EmptyState title={`No ${state.toLowerCase().replace('_', ' ')} approvals`} description="The queue is currently clear for this filter." />
            ) : (
              <div className="space-y-3">
                {approvals.data.items.map((approval) => <ApprovalCard key={approval.id} approval={approval} onDecision={openDecision} clear={clear} />)}
                {(approvals.data.totalPages ?? 1) > 1 && (
                  <div className="flex items-center justify-between text-xs text-muted">
                    <span>Page {(approvals.data.page ?? page) + 1} of {approvals.data.totalPages}</span>
                    <div className="flex gap-2">
                      <Button size="sm" variant="ghost" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous</Button>
                      <Button size="sm" variant="ghost" disabled={page + 1 >= approvals.data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</Button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </Panel>

          {clear && (
            <Notice tone="neutral" title="How approval works">
              Manual approval allows an eligible order to proceed to the next safety check. It does not bypass risk limits, emergency stop, market-session rules or stale-market-data checks.
            </Notice>
          )}
        </>
      )}


    </div>
  );
}
