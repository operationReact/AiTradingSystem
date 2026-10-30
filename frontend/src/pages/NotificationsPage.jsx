import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApp } from '../context/AppContext.jsx';
import { apiFetch } from '../lib/api.js';
import { useOperatorEvidence } from '../hooks/useOperatorEvidence.js';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import NotificationsView from '../components/notifications/NotificationsView.jsx';

export default function NotificationsPage() {
  const { apiKey } = useApp();
  const client = useQueryClient();
  const [activeOnly, setActiveOnly] = useState(true);
  const [page, setPage] = useState(0);
  const query = useOperatorEvidence(['notifications', activeOnly, page], `/notifications?activeOnly=${activeOnly}&page=${page}&size=20`);
  const ack = useMutation({
    mutationFn: id => apiFetch(`/notifications/${encodeURIComponent(id)}/acknowledge`, { apiKey, method: 'POST' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['operator-evidence', 'notifications'] }),
  });
  const props = { query, activeOnly, page, onPage: setPage, onActiveOnly: value => { setActiveOnly(value); setPage(0); ack.reset(); },
    pending: ack.isPending ? ack.variables : null, error: ack.error, success: ack.isSuccess,
    onAcknowledge: id => { if (!ack.isPending) ack.mutate(id); } };
  return <ExperienceBoundary pro={<NotificationsView {...props} />} clear={<NotificationsView {...props} clear />} />;
}
