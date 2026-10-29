import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../lib/api.js';
import { useApp } from '../context/AppContext.jsx';

const marketDataKey = ['market-data'];

function useAuthedQuery(queryKey, path, enabled = true) {
  const { apiKey } = useApp();
  return useQuery({
    queryKey: [...queryKey, apiKey ? 'authed' : 'anon'],
    queryFn: ({ signal }) => apiFetch(path, { apiKey, signal }),
    enabled: !!apiKey && enabled,
    refetchInterval: false,
    retry: 1,
  });
}

export function useMarketDataQuality({ instrumentId, interval, enabled = true }) {
  const params = new URLSearchParams({
    instrumentId: String(instrumentId ?? 0),
    interval: interval || '',
  });
  return useAuthedQuery(
    [...marketDataKey, 'quality', instrumentId, interval],
    `/market-data/quality/latest?${params}`,
    enabled && Number(instrumentId) > 0 && !!interval,
  );
}

export function useEvaluateMarketDataQuality() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ instrumentId, interval, from, to }) => {
      const params = new URLSearchParams({
        instrumentId: String(instrumentId), interval, from, to,
      });
      return apiFetch(`/market-data/quality/evaluate?${params}`, { apiKey, method: 'POST' });
    },
    onSettled: () => qc.invalidateQueries({ queryKey: marketDataKey }),
  });
}

export function useMarketCalendar({ from, to, enabled = true }) {
  const params = new URLSearchParams({ from: from || '', to: to || '' });
  return useAuthedQuery(
    [...marketDataKey, 'calendar', from, to],
    `/market-data/calendar?${params}`,
    enabled && !!from && !!to && from <= to,
  );
}

export function useReconcileMarketData() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ instrumentId, interval, from, to }) => {
      const params = new URLSearchParams({
        instrumentId: String(instrumentId), interval, from, to,
      });
      return apiFetch(`/market-data/reconcile?${params}`, { apiKey, method: 'POST' });
    },
    onSettled: () => qc.invalidateQueries({ queryKey: marketDataKey }),
  });
}
