import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../lib/api.js';
import { useApp } from '../context/AppContext.jsx';

const LIVE_MS = 30_000;

function usePaperQuery(key, path, { poll = true } = {}) {
  const { apiKey } = useApp();
  return useQuery({
    queryKey: [...key, apiKey ? 'authed' : 'anon'],
    queryFn: ({ signal }) => apiFetch(path, { apiKey, signal }),
    enabled: !!apiKey,
    refetchInterval: poll ? LIVE_MS : false,
  });
}

export const useCanonicalPortfolio = () => usePaperQuery(['paper', 'canonical-portfolio'], '/paper-portfolio');
export const usePaperPositions = () => usePaperQuery(['paper', 'positions'], '/paper-positions');
export const usePaperFills = (limit = 100) => usePaperQuery(['paper', 'fills', limit], `/paper-fills?limit=${limit}`);
export const usePaperCashLedger = (limit = 100) => usePaperQuery(['paper', 'cash-ledger', limit], `/paper-cash-ledger?limit=${limit}`);
export const usePaperReconciliationRuns = (limit = 20) => usePaperQuery(['paper', 'reconciliation', limit], `/paper-reconciliation/runs?limit=${limit}`, { poll: false });

export function useRunPaperReconciliation() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch('/paper-reconciliation', { apiKey, method: 'POST' }),
    onSettled: () => Promise.all([
      qc.invalidateQueries({ queryKey: ['paper', 'reconciliation'] }),
      qc.invalidateQueries({ queryKey: ['paper', 'canonical-portfolio'] }),
    ]),
  });
}
