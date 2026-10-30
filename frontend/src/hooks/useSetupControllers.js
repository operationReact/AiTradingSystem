import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApp } from '../context/AppContext.jsx';
import { apiFetch } from '../lib/api.js';
import { requestState } from '../lib/viewModel.js';
import { useImportInstrumentMaster, useInstrumentCatalogueStatus, useInstrumentSearch, useRecentInstrumentImports,
  useCandleBackfills, useCandles, useStartCandleBackfill, useResumeCandleBackfill, useAngelOneStatus } from './useApi.js';
import { useEvaluateMarketDataQuality, useMarketCalendar, useMarketDataQuality, useReconcileMarketData } from './useMarketDataOperations.js';

const project = query => ({ ...requestState(query), refetch: () => query.refetch() });

function todayInIndia() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date());
  const part = (type) => parts.find((item) => item.type === type)?.value;
  return part('year') + '-' + part('month') + '-' + part('day');
}
function shiftDate(value, days) {
  const date = new Date(value + 'T00:00:00Z');
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}
function toInstant(value, end = false) {
  const date = new Date(`${value}T00:00:00+05:30`);
  if (end) date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString();
}

export function useInstrumentMasterController() {
  const { apiKey } = useApp();
  const [draftQuery, setDraftQuery] = useState('');
  const [draftExchange, setDraftExchange] = useState('');
  const [draftType, setDraftType] = useState('');
  const [criteria, setCriteria] = useState({ q: '', exchange: '', type: '' });
  const [page, setPage] = useState(0);
  const pageSize = 20;

  const status = useInstrumentCatalogueStatus();
  const search = useInstrumentSearch({ ...criteria, page, size: pageSize });
  const imports = useRecentInstrumentImports(5);
  const importMaster = useImportInstrumentMaster();

  const submitSearch = (event) => {
    event.preventDefault();
    setCriteria({ q: draftQuery.trim(), exchange: draftExchange, type: draftType });
    setPage(0);
  };

  const clearSearch = () => {
    setDraftQuery('');
    setDraftExchange('');
    setDraftType('');
    setCriteria({ q: '', exchange: '', type: '' });
    setPage(0);
  };

  const catalogue = status.data;
  const latestAttempt = importMaster.data;

  return { connected: !!apiKey, draftQuery, setDraftQuery, draftExchange, setDraftExchange, draftType, setDraftType,
    criteria, page, setPage, pageSize, status: project(status), search: project(search), imports: project(imports),
    importMaster: requestState(importMaster), catalogue, latestAttempt, submitSearch, clearSearch,
    onImport: () => { if (apiKey && !importMaster.isPending) importMaster.mutate(); } };
}

export function useHistoricalDataController() {
  const { apiKey } = useApp();
  const [instrumentText, setInstrumentText] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [selected, setSelected] = useState(null);
  const [interval, setInterval] = useState('FIVE_MINUTE');
  const today = todayInIndia();
  const [toDate, setToDate] = useState(today);
  const [fromDate, setFromDate] = useState(shiftDate(today, -29));
  const [page, setPage] = useState(0);
  const [latestRun, setLatestRun] = useState(null);

  useEffect(() => {
    const timer = setTimeout(() => setSearchTerm(instrumentText.trim()), 300);
    return () => clearTimeout(timer);
  }, [instrumentText]);

  const matches = useInstrumentSearch({
    q: searchTerm, page: 0, size: 10, enabled: !!apiKey && !selected && searchTerm.length > 0,
  });
  const backfills = useCandleBackfills(10);
  const start = useStartCandleBackfill();
  const resume = useResumeCandleBackfill();
  const candles = useCandles({
    instrumentId: selected?.id, interval, fromDate, toDate, page, size: 100,
    enabled: !!selected && !!fromDate && !!toDate && fromDate <= toDate,
  });

  const submitBackfill = (event) => {
    event.preventDefault();
    if (!apiKey || !selected || !fromDate || !toDate || fromDate > toDate || start.isPending) return;
    start.mutate({ instrumentId: selected.id, interval, fromDate, toDate }, { onSuccess: setLatestRun });
  };
  const resumeRun = runId => { if (apiKey && !resume.isPending) resume.mutate(runId, { onSuccess: setLatestRun }); };
  const chooseInstrument = (instrument) => {
    setSelected(instrument);
    setInstrumentText(instrument.tradingSymbol);
    setSearchTerm('');
    setPage(0);
    setLatestRun(null);
  };
  const changingInstrument = () => {
    setSelected(null);
    setSearchTerm('');
    setInstrumentText('');
    setLatestRun(null);
  };
  const viewRun = run => {
    chooseInstrument({ id: run.instrumentId, exchange: run.exchange, tradingSymbol: run.tradingSymbol, symbolToken: run.symbolToken });
    setInterval(run.interval); setFromDate(run.fromDate); setToDate(run.toDate);
  };
  const latestError = start.error || resume.error;
  const displayedRun = latestRun || start.data || resume.data;

  return { connected: !!apiKey, instrumentText, setInstrumentText, searchTerm, selected, interval, setInterval, today,
    fromDate, setFromDate, toDate, setToDate, page, setPage, matches: project(matches), backfills: project(backfills), candles: project(candles),
    start: requestState(start), resume: { ...requestState(resume), variables: resume.variables }, submitBackfill, resumeRun,
    chooseInstrument, changingInstrument, viewRun, latestError, displayedRun };
}

export function useMarketOperationsController() {
  const { apiKey } = useApp();
  const today = todayInIndia();
  const [instrumentId, setInstrumentId] = useState('');
  const [interval, setInterval] = useState('FIVE_MINUTE');
  const [from, setFrom] = useState(shiftDate(today, -7));
  const [to, setTo] = useState(today);
  const [evaluation, setEvaluation] = useState(null);
  const [reconciliation, setReconciliation] = useState(null);
  const [selectedRun, setSelectedRun] = useState(null);
  const validInstrument = Number.isSafeInteger(Number(instrumentId)) && Number(instrumentId) > 0;
  const validRange = !!from && !!to && from <= to;

  const latestQuality = useMarketDataQuality({ instrumentId: Number(instrumentId), interval, enabled: validInstrument });
  const evaluate = useEvaluateMarketDataQuality();
  const reconcile = useReconcileMarketData();
  const calendar = useMarketCalendar({ from, to, enabled: validRange });
  const provider = useAngelOneStatus();
  const backfills = useCandleBackfills(10);

  const instantRange = useMemo(() => validRange ? { from: toInstant(from), to: toInstant(to, true) } : null, [from, to, validRange]);
  const runEvaluate = () => {
    if (!apiKey || !validInstrument || !instantRange || evaluate.isPending) return;
    evaluate.mutate({ instrumentId: Number(instrumentId), interval, ...instantRange }, { onSuccess: setEvaluation });
  };
  const runReconcile = () => {
    if (!apiKey || !validInstrument || !instantRange || reconcile.isPending) return;
    reconcile.mutate({ instrumentId: Number(instrumentId), interval, ...instantRange }, { onSuccess: setReconciliation });
  };

  return { connected: !!apiKey, today, instrumentId, setInstrumentId, interval, setInterval, from, setFrom, to, setTo,
    evaluation, setEvaluation, reconciliation, setReconciliation, selectedRun, setSelectedRun, validInstrument, validRange,
    latestQuality: project(latestQuality), evaluate: requestState(evaluate), reconcile: requestState(reconcile),
    calendar: project(calendar), provider: project(provider), backfills: project(backfills), runEvaluate, runReconcile };
}

export function useApprovalSettingsController() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  const policies = useQuery({
    queryKey: ['approvals', 'policies', apiKey ? 'authed' : 'anon'],
    queryFn: ({ signal }) => apiFetch('/approval-policies', { apiKey, signal }),
    enabled: !!apiKey,
    staleTime: 15_000,
  });
  const update = useMutation({
    mutationFn: ({ id, approvalMode }) => apiFetch(`/approval-policies/${id}`, {
      apiKey,
      method: 'PUT',
      body: { approvalMode },
    }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['approvals', 'policies'] }),
  });


  return { connected: !!apiKey, policies: project(policies), update: requestState(update),
    onUpdateMode: (policy, approvalMode) => {
      if (apiKey && !update.isPending && policy.active && policy.approvalMode !== approvalMode
          && ['MANUAL', 'AUTO', 'HYBRID'].includes(approvalMode)) update.mutate({ id: policy.id, approvalMode });
    } };
}
