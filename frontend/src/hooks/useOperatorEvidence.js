import { useQuery } from '@tanstack/react-query';
import { useApp } from '../context/AppContext.jsx';
import { apiFetch } from '../lib/api.js';

/** Route controllers own this query; a design switch never changes its identity or authorization. */
export function useOperatorEvidence(key, path, enabled = true) {
  const { apiKey } = useApp();
  return useQuery({
    queryKey: ['operator-evidence', ...key, apiKey ? 'authed' : 'anon'],
    queryFn: ({ signal }) => apiFetch(path, { apiKey, signal }),
    enabled: !!apiKey && enabled,
    staleTime: 30_000,
  });
}
