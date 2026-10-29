import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiDownload, apiFetch } from '../lib/api.js';
import { useApp } from '../context/AppContext.jsx';

const studioKeys = {
  runs: (params) => ['backtests', 'runs', params],
  detail: (id) => ['backtests', 'run', id],
  equity: (id) => ['backtests', 'equity', id],
  walkForward: (id) => ['backtests', 'walk-forward', id],
};

function authedQuery(key, path, enabled = true, options = {}) {
  const { apiKey } = useApp();
  return useQuery({
    queryKey: [...key, apiKey ? 'authed' : 'anon'],
    queryFn: ({ signal }) => apiFetch(path, { apiKey, signal }),
    enabled: !!apiKey && enabled,
    refetchInterval: false,
    ...options,
  });
}

export function useBacktestRuns({ instrumentId, strategy = '', page = 0, size = 20 } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (instrumentId) params.set('instrumentId', String(instrumentId));
  if (strategy) params.set('strategy', strategy);
  return authedQuery(
    studioKeys.runs({ instrumentId: instrumentId || null, strategy, page, size }),
    `/backtests?${params.toString()}`,
    true,
    { placeholderData: keepPreviousData, retry: 1 },
  );
}

export const useBacktestDetail = (id) => authedQuery(studioKeys.detail(id), `/backtests/${id}`, !!id, { retry: 1 });
export const useBacktestEquity = (id) => authedQuery(studioKeys.equity(id), `/backtests/${id}/equity`, !!id, { retry: 1 });
export const useWalkForwardDetail = (id) => authedQuery(studioKeys.walkForward(id), `/backtests/walk-forward/${id}`, !!id, { retry: 1 });

export function useRunBacktest() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiFetch('/backtests', { apiKey, method: 'POST', body }),
    onSettled: () => qc.invalidateQueries({ queryKey: ['backtests', 'runs'] }),
  });
}

export function useRunWalkForward() {
  const { apiKey } = useApp();
  return useMutation({
    mutationFn: (body) => apiFetch('/backtests/walk-forward', { apiKey, method: 'POST', body }),
  });
}

export function useExportBacktestCsv() {
  const { apiKey } = useApp();
  return useMutation({
    mutationFn: async ({ id, symbol }) => {
      const { blob, contentDisposition } = await apiDownload(`/backtests/${id}/export.csv`, { apiKey });
      const match = /filename="?([^";]+)"?/i.exec(contentDisposition);
      const filename = match?.[1] || `backtest-${symbol || id}.csv`;
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = filename;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      return filename;
    },
  });
}
