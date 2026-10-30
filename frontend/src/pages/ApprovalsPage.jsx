import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../lib/api.js';
import { requestState } from '../lib/viewModel.js';
import { useApp } from '../context/AppContext.jsx';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import ProApprovalView from '../components/approvals/pro/ProApprovalView.jsx';
import ClearApprovalView from '../components/approvals/clear/ClearApprovalView.jsx';
import ApprovalDecisionDialog from '../components/approvals/shared/ApprovalDecisionDialog.jsx';
const PAGE_SIZE = 20;

export default function ApprovalsPage() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  const [state, setState] = useState('PENDING');
  const [page, setPage] = useState(0);
  const [decision, setDecision] = useState(null);
  const [reason, setReason] = useState('');
  const [decisionResult, setDecisionResult] = useState(null);

  const approvals = useQuery({
    queryKey: ['approvals', 'queue', state, page, apiKey ? 'authed' : 'anon'],
    queryFn: ({ signal }) => apiFetch(`/approvals?state=${state}&page=${page}&size=${PAGE_SIZE}`, { apiKey, signal }),
    enabled: !!apiKey,
    refetchInterval: state === 'PENDING' ? 15_000 : false,
    placeholderData: (previous) => previous,
  });
  const pendingCount = useQuery({
    queryKey: ['approvals', 'pending-count', apiKey ? 'authed' : 'anon'],
    queryFn: ({ signal }) => apiFetch('/approvals?state=PENDING&page=0&size=1', { apiKey, signal }),
    enabled: !!apiKey,
    refetchInterval: 15_000,
  });
  const mutate = useMutation({
    mutationFn: async ({ approval, action, reason: decisionReason }) => {
      const result = await apiFetch(`/approvals/${approval.id}/${action}`, {
        apiKey,
        method: 'POST',
        body: {
          expectedRowVersion: approval.rowVersion,
          actor: 'operator-ui',
          reason: decisionReason?.trim() || undefined,
        },
      });
      if (result.state === 'APPROVED' || result.state === 'AUTO_APPROVED') {
        try {
          const order = await apiFetch(`/paper-orders/${result.orderId}`, { apiKey });
          return { approval: result, order: order.order };
        } catch {
          return { approval: result, orderUnavailable: true };
        }
      }
      return { approval: result };
    },
    onSuccess: (result) => {
      setDecisionResult(result);
      setDecision(null);
      setReason('');
      return Promise.all([
        qc.invalidateQueries({ queryKey: ['approvals'] }),
        qc.invalidateQueries({ queryKey: ['paper'] }),
      ]);
    },
  });

  const openDecision = (approval, action) => { mutate.reset(); setDecision({ approval, action }); setReason(''); };
  const totalPending = pendingCount.data?.totalItems ?? pendingCount.data?.total ?? 0;

  const model = { connected: !!apiKey, totalPending, decisionResult, state, page, approvals: requestState(approvals) };
  const actions = { onFilter: value => { setState(value); setPage(0); }, setPage, openDecision, onRetry: () => approvals.refetch() };
  const confirm = () => { if (apiKey && decision && !mutate.isPending) mutate.mutate({ ...decision, reason }); };
  return <>
    <ExperienceBoundary pro={<ProApprovalView model={model} actions={actions} />} clear={<ClearApprovalView model={model} actions={actions} />} />
    <ApprovalDecisionDialog connected={!!apiKey} decision={decision} reason={reason} setReason={setReason} setDecision={setDecision} mutation={requestState(mutate)} onConfirm={confirm} />
  </>;
}
