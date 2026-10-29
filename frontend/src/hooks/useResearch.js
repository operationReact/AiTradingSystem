import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../lib/api.js';
import { useApp } from '../context/AppContext.jsx';

const keys = {
  strategies: ['research', 'strategies'],
  scans: (params) => ['research', 'scans', params],
  scan: (id) => ['research', 'scan', id],
  candidates: (id, limit) => ['research', 'candidates', id, limit],
  diagnostics: (id) => ['research', 'diagnostics', id],
};

function useAuthedQuery(queryKey, path, options = {}) {
  const { apiKey } = useApp();
  return useQuery({
    queryKey: [...queryKey, apiKey ? 'authed' : 'anon'],
    queryFn: ({ signal }) => apiFetch(path, { apiKey, signal }),
    enabled: !!apiKey && (options.enabled ?? true),
    refetchInterval: options.poll ? 5000 : false,
    ...options,
  });
}

export function useResearchStrategies() {
  return useAuthedQuery(keys.strategies, '/research/strategies', { poll: false });
}

export function useResearchScans(params = {}) {
  const { id } = params;
  return useResearchScan(id);
}

export function useResearchScan(id) {
  return useAuthedQuery(keys.scan(id), `/research/scans/${id}`, { enabled: !!id, poll: true });
}

export function useResearchCandidates(id, limit = 50) {
  return useAuthedQuery(keys.candidates(id, limit), `/research/scans/${id}/candidates?limit=${limit}`, { enabled: !!id, poll: false });
}

export function useResearchDiagnostics(id) {
  return useAuthedQuery(keys.diagnostics(id), `/research/scans/${id}/diagnostics`, { enabled: !!id, poll: false });
}

export function useStartResearchScan() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ interval = 'FIVE_MINUTE', limit = 100 }) =>
      apiFetch(`/research/scans?interval=${encodeURIComponent(interval)}&limit=${limit}`, { apiKey, method: 'POST' }),
    onSuccess: (run) => {
      qc.setQueryData([...keys.scan(run.id), apiKey ? 'authed' : 'anon'], run);
      qc.invalidateQueries({ queryKey: ['research'] });
    },
  });
}
