import { Button, Modal, Notice } from '../../ui.jsx';

export default function ApprovalDecisionDialog({ connected, decision, reason, setReason, setDecision, mutation, onConfirm }) {
  return (
      <Modal
        open={!!decision}
        onOpenChange={(open) => !open && setDecision(null)}
        title={decision?.action === 'approve' ? 'Approve this order?' : 'Reject this order?'}
        description="This action is concurrency-safe. The server returns the authoritative current state if another operator already decided it."
        footer={
          <>
            <Button onClick={() => setDecision(null)}>Cancel</Button>
            <Button
              variant={decision?.action === 'approve' ? 'primary' : 'danger'}
              loading={mutation.isPending}
              disabled={!connected || !decision || mutation.isPending}
              onClick={onConfirm}
            >
              Confirm {decision?.action}
            </Button>
          </>
        }
      >
        <div className="space-y-3 text-sm">
          <p className="text-muted">Order <span className="font-medium text-text">{decision?.approval?.orderId}</span></p>
          <label className="flex flex-col gap-1 text-xs text-muted">
            Decision reason (optional, max 300 characters)
            <textarea
              value={reason}
              maxLength={300}
              rows={3}
              onChange={(e) => setReason(e.target.value)}
              className="rounded-md border border-border bg-surface-2 px-2.5 py-2 text-sm text-text focus:border-accent focus:outline-none"
            />
          </label>
          {mutation.isError && <Notice tone="loss" title="Decision failed">{mutation.error?.message}</Notice>}
          <Notice tone="neutral" title="Final confirmation required">
            There is deliberately no one-key approve shortcut. Navigation hotkeys may be added later, but irreversible decisions always require explicit confirmation.
          </Notice>
        </div>
      </Modal>
  );
}
