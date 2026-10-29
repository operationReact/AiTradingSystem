import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import { apiFetch } from '../lib/api.js';
import { useApp } from '../context/AppContext.jsx';

/** Polling interval for live operational data. Chart and history data are not polled. */
const LIVE_MS = 30_000;

export const keys = {
  summary: ['dashboard', 'summary'],
  equity: (days) => ['dashboard', 'equity', days],
  portfolio: ['paper', 'portfolio'],
  trades: (params) => ['paper', 'trades', params],
  order: (id) => ['paper', 'order', id],
  signals: (limit = 50) => ['signals', limit],
  riskConfig: ['risk', 'config'],
  riskPolicy: ['risk', 'policy'],
  riskPolicies: (params) => ['risk', 'policies', params],
  riskPolicyDetail: (id) => ['risk', 'policy', id],
  riskDecision: (id) => ['risk', 'decision', id],
  riskDecisions: (params) => ['risk', 'decisions', params],
  riskPositionSize: (input) => ['risk', 'position-size', input],
  newsStatus: ['news', 'status'],
  newsArticles: (params) => ['news', 'articles', params],
  executionMode: ['system', 'execution-mode'],
  angelOneStatus: ['providers', 'angel-one', 'status'],
  instrumentCatalogueStatus: ['instruments', 'status'],
  instrumentSearch: (params) => ['instruments', 'search', params],
  instrumentImports: (limit) => ['instruments', 'imports', limit],
  marketBackfills: (limit) => ['market-data', 'backfills', limit],
  candles: (params) => ['market-data', 'candles', params],
  backtestValidations: (limit) => ['backtests', 'validation', limit],
  backtestValidation: (id) => ['backtests', 'validation', id],
};

function useAuthedQuery(key, path, { poll = true, enabled = true, ...options } = {}) {
  const { apiKey } = useApp();
  return useQuery({
    queryKey: [...key, apiKey ? 'authed' : 'anon'],
    queryFn: ({ signal }) => apiFetch(path, { apiKey, signal }),
    enabled: !!apiKey && enabled,
    refetchInterval: poll ? LIVE_MS : false,
    ...options,
  });
}

export const useDashboardSummary = () => useAuthedQuery(keys.summary, '/dashboard/summary');

export const useEquityCurve = (days) =>
  useAuthedQuery(keys.equity(days), `/dashboard/equity-curve?days=${days}`, { poll: false });

export const usePortfolio = () => useAuthedQuery(keys.portfolio, '/paper-trades/portfolio');

export function useOrders({ page = 0, size = 10, status = '', symbol = '' }) {
  const params = new URLSearchParams({ page, size });
  if (status) params.set('status', status);
  if (symbol) params.set('symbol', symbol);
  return useAuthedQuery(keys.trades({ page, size, status, symbol }), `/paper-trades?${params}`, {
    placeholderData: keepPreviousData,
  });
}

export const useOrderDetail = (id) =>
  useAuthedQuery(keys.order(id), `/paper-trades/${id}`, { poll: false, enabled: !!id });

export const useSignals = (limit = 50) =>
  useAuthedQuery(keys.signals(limit), `/signals?limit=${limit}`, { poll: false });

/** Evaluates the deterministic strategy using stored OHLCV only, then refreshes persisted signal history. */
export function useEvaluateStrategy() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ instrumentId, interval }) =>
      apiFetch(`/strategies/${instrumentId}/evaluate?interval=${encodeURIComponent(interval)}`, {
        apiKey, method: 'POST',
      }),
    onSettled: () => qc.invalidateQueries({ queryKey: ['signals'] }),
  });
}

export const useRiskConfig = () => useAuthedQuery(keys.riskConfig, '/risk/config', { poll: false });

/** Active immutable persisted risk policy used by new risk evaluations. */
export const useRiskPolicy = () =>
  useAuthedQuery(keys.riskPolicy, '/risk/policy', { poll: false, retry: 1 });

export function useRiskPolicies({ page = 0, size = 20 } = {}) {
  const params = { page, size };
  return useAuthedQuery(keys.riskPolicies(params), `/risk/policies?page=${page}&size=${size}`, {
    poll: false,
    placeholderData: keepPreviousData,
  });
}

export const useRiskPolicyDetail = (id) =>
  useAuthedQuery(keys.riskPolicyDetail(id), `/risk/policies/${id}`, {
    poll: false, enabled: !!id, retry: 1,
  });

export function useCreateRiskPolicy() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiFetch('/risk/policies', { apiKey, method: 'POST', body }),
    onSettled: () => Promise.all([
      qc.invalidateQueries({ queryKey: ['risk', 'policy'] }),
      qc.invalidateQueries({ queryKey: ['risk', 'policies'] }),
      qc.invalidateQueries({ queryKey: ['risk', 'config'] }),
    ]),
  });
}

export const useRiskDecisions = ({ page = 0, size = 10 } = {}) =>
  useAuthedQuery(keys.riskDecisions({ page, size }), `/risk/decisions?page=${page}&size=${size}`, {
    placeholderData: keepPreviousData,
  });

export const useRiskDecisionDetail = (id) =>
  useAuthedQuery(keys.riskDecision(id), `/risk/decisions/${id}`, {
    poll: false, enabled: !!id, retry: 1,
  });

/** Advisory only; the server reloads authoritative policy/portfolio state and actual orders re-evaluate risk. */
export function usePositionSizeSuggestion() {
  const { apiKey } = useApp();
  return useMutation({
    mutationFn: (input) => apiFetch('/risk/position-size', { apiKey, method: 'POST', body: input }),
  });
}

export const useNewsStatus = () => useAuthedQuery(keys.newsStatus, '/news/status', { poll: false });

export const useNewsArticles = ({ page = 0, size = 5 } = {}) =>
  useAuthedQuery(keys.newsArticles({ page, size }), `/news/articles?page=${page}&size=${size}`, {
    poll: false,
    placeholderData: keepPreviousData,
  });

/** Angel One provider state from the server. Secrets and tokens never reach the browser. */
export const useAngelOneStatus = () =>
  useAuthedQuery(keys.angelOneStatus, '/providers/angel-one/status', { poll: false, retry: 1 });

/** Runs a server-side session check against Angel One. The response replaces the cached status. */
export function useTestAngelOneConnection() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch('/providers/angel-one/test-connection', { apiKey, method: 'POST' }),
    onSuccess: (data) => qc.setQueryData([...keys.angelOneStatus, apiKey ? 'authed' : 'anon'], data),
  });
}

/** Active Angel One instrument catalogue metadata. */
export const useInstrumentCatalogueStatus = () =>
  useAuthedQuery(keys.instrumentCatalogueStatus, '/instruments/status', { poll: false, retry: 1 });

/** Bounded server-side search against the active catalogue version. */
export function useInstrumentSearch({ q = '', exchange = '', type = '', page = 0, size = 20, enabled = true } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (q) params.set('q', q);
  if (exchange) params.set('exchange', exchange);
  if (type) params.set('type', type);
  return useAuthedQuery(
    keys.instrumentSearch({ q, exchange, type, page, size }),
    '/instruments/search?' + params.toString(),
    { poll: false, enabled, placeholderData: keepPreviousData },
  );
}

/** Recent import attempts, including rejected/incomplete catalogue files. */
export const useRecentInstrumentImports = (limit = 5) =>
  useAuthedQuery(keys.instrumentImports(limit), '/instruments/imports?limit=' + limit, { poll: false });

/** Downloads the catalogue on the backend and refreshes search/status/history after the attempt. */
export function useImportInstrumentMaster() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch('/instruments/import', { apiKey, method: 'POST' }),
    onSettled: () => qc.invalidateQueries({ queryKey: ['instruments'] }),
  });
}

/** Runs one structured AI analysis for an article. The backend reuses a valid result unless forced. */
export function useAnalyzeArticle() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id) => apiFetch(`/news/articles/${id}/analyze`, { apiKey, method: 'POST' }),
    onSettled: () => qc.invalidateQueries({ queryKey: ['news'] }),
  });
}

/**
 * Backend-reported execution mode. The UI never assumes a mode: it only reflects the last
 * successful response from the server. Failures surface as an error state, not as a mode.
 */
export const useExecutionMode = () =>
  useAuthedQuery(keys.executionMode, '/system/execution-mode', { poll: false, retry: 1 });

/** Requests a mode change. The backend decides; the result is written to the cache only when the server accepts it. */
export function useChangeExecutionMode() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ mode, confirmation }) =>
      apiFetch('/system/execution-mode', { apiKey, method: 'PUT', body: { mode, confirmation } }),
    onSuccess: (data) => qc.setQueryData([...keys.executionMode, apiKey ? 'authed' : 'anon'], data),
    onError: (err) => {
      if (err?.status === 409 && err.body) {
        qc.setQueryData([...keys.executionMode, apiKey ? 'authed' : 'anon'], (prev) => ({ ...prev, ...err.body, liveAvailable: false }));
      }
    },
  });
}

/** Invalidates everything that a paper order or a risk change can affect. */
function useInvalidatePaper() {
  const qc = useQueryClient();
  return () =>
    Promise.all(
      [['dashboard'], ['paper'], ['risk']].map((queryKey) => qc.invalidateQueries({ queryKey })),
    );
}

/**
 * Submits a paper-only simulated order. The caller supplies the idempotency key so a retried
 * submission of the same dialog session reuses it and the backend deduplicates the order.
 */
export function useSimulateTrade() {
  const { apiKey } = useApp();
  const invalidate = useInvalidatePaper();
  return useMutation({
    mutationFn: ({ idempotencyKey, body }) =>
      apiFetch('/paper-trades/simulate', {
        apiKey,
        method: 'POST',
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSettled: invalidate,
  });
}

export function useEmergencyStop() {
  const { apiKey } = useApp();
  const invalidate = useInvalidatePaper();
  return useMutation({
    mutationFn: ({ halted, reason }) =>
      apiFetch('/risk/emergency-stop', { apiKey, method: 'PUT', body: { halted, reason } }),
    onSettled: invalidate,
  });
}

/** Stored historical OHLCV, scoped to a selected instrument/interval/date range. */
export function useCandles({ instrumentId, interval, fromDate, toDate, page = 0, size = 100, enabled = true }) {
  const params = new URLSearchParams({
    instrumentId: String(instrumentId ?? 0), interval, fromDate, toDate, page: String(page), size: String(size),
  });
  return useAuthedQuery(
    keys.candles({ instrumentId, interval, fromDate, toDate, page, size }),
    '/market-data/candles?' + params.toString(),
    { poll: false, enabled: enabled && !!instrumentId && !!interval && !!fromDate && !!toDate, placeholderData: keepPreviousData },
  );
}

export const useCandleBackfills = (limit = 10) =>
  useAuthedQuery(keys.marketBackfills(limit), '/market-data/backfills?limit=' + limit, { poll: false });

export function useStartCandleBackfill() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiFetch('/market-data/backfills', { apiKey, method: 'POST', body }),
    onSettled: () => qc.invalidateQueries({ queryKey: ['market-data'] }),
  });
}

export function useResumeCandleBackfill() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id) => apiFetch('/market-data/backfills/' + id + '/resume', { apiKey, method: 'POST' }),
    onSettled: () => qc.invalidateQueries({ queryKey: ['market-data'] }),
  });
}

/** Persisted out-of-sample validation history; keys stay in memory and are sent only as request headers. */
export const useBacktestValidations = (limit = 20) =>
  useAuthedQuery(keys.backtestValidations(limit), `/backtests/validation?limit=${limit}`, { poll: false, retry: 1 });

/** Full persisted training/holdout report with both independently simulated trade lists. */
export const useBacktestValidationDetail = (id) =>
  useAuthedQuery(keys.backtestValidation(id), `/backtests/validation/${id}`, {
    poll: false, enabled: !!id, retry: 1,
  });

/** Creates a new fixed-parameter PAPER validation and refreshes saved validation history. */
export function useRunBacktestValidation() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiFetch('/backtests/validation', { apiKey, method: 'POST', body }),
    onSettled: () => qc.invalidateQueries({ queryKey: ['backtests', 'validation'] }),
  });
}
