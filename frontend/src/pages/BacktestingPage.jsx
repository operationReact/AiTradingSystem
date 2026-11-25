import { useEffect, useMemo, useState } from 'react';
import { requestState } from '../lib/viewModel.js';
import { useApp } from '../context/AppContext.jsx';
import { useBacktestValidationDetail, useBacktestValidations, useInstrumentSearch, useRunBacktestValidation } from '../hooks/useApi.js';
import { useBacktestDetail, useBacktestEquity, useBacktestRuns, useExportBacktestCsv, useRunBacktest, useRunWalkForward } from '../hooks/useBacktestStudio.js';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import ProBacktestView from '../components/backtest/pro/ProBacktestView.jsx';
import ClearBacktestView from '../components/backtest/clear/ClearBacktestView.jsx';

function todayInIndia() {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
  const part = (type) => parts.find((item) => item.type === type)?.value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}
function shiftDate(value, days) {
  const date = new Date(`${value}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

export default function BacktestingPage() {
  const { apiKey } = useApp();
  const today = todayInIndia();
  const defaultTo = shiftDate(today, -1);
  const [form, setForm] = useState({ interval: 'ONE_DAY', fromDate: shiftDate(defaultTo, -179), toDate: defaultTo, initialCapital: '80000' });
  const [instrumentText, setInstrumentText] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedInstrument, setSelectedInstrument] = useState(null);
  const [selectedRunId, setSelectedRunId] = useState(null);
  const [walkForward, setWalkForward] = useState(null);
  const [assumptionsOpen, setAssumptionsOpen] = useState(false);
  const [validationId, setValidationId] = useState(null);

  useEffect(() => { const timer = setTimeout(() => setSearchTerm(instrumentText.trim()), 250); return () => clearTimeout(timer); }, [instrumentText]);

  const matches = useInstrumentSearch({ q: searchTerm, exchange: 'NSE', type: 'EQ', page: 0, size: 12, enabled: !!apiKey && !selectedInstrument && searchTerm.length > 0 });
  const runs = useBacktestRuns({ instrumentId: selectedInstrument?.id, page: 0, size: 20 });
  const detail = useBacktestDetail(selectedRunId);
  const equity = useBacktestEquity(selectedRunId);
  const runMutation = useRunBacktest();
  const walkMutation = useRunWalkForward();
  const exportMutation = useExportBacktestCsv();

  const validations = useBacktestValidations(10);
  const validationDetail = useBacktestValidationDetail(validationId);
  const validationMutation = useRunBacktestValidation();

  const selectedDetail = useMemo(() => {
    if (runMutation.data?.run?.id === selectedRunId) return runMutation.data;
    return detail.data;
  }, [runMutation.data, selectedRunId, detail.data]);
  const run = selectedDetail?.run || runs.data?.items?.[0] || null;
  const totalRuns = runs.data?.totalElements || 0;
  const validDates = form.fromDate && form.toDate && form.fromDate < form.toDate;
  const validCapital = Number(form.initialCapital) >= 10000 && Number(form.initialCapital) <= 100000000;
  const canRun = !!apiKey && !!selectedInstrument && validDates && validCapital && !runMutation.isPending;

  const changeForm = (key, value) => setForm((current) => ({ ...current, [key]: value }));
  const selectInstrument = (item) => { setSelectedInstrument(item); setInstrumentText(item.tradingSymbol); setSearchTerm(''); };
  const clearInstrument = () => { setSelectedInstrument(null); setInstrumentText(''); setSearchTerm(''); setSelectedRunId(null); setWalkForward(null); };

  const payload = () => ({ instrumentId: selectedInstrument.id, interval: form.interval, fromDate: form.fromDate, toDate: form.toDate, initialCapital: Number(form.initialCapital) });
  const submit = (event) => {
    event.preventDefault();
    if (!canRun) return;
    runMutation.mutate(payload(), { onSuccess: (data) => { setSelectedRunId(data.run.id); setWalkForward(null); runs.refetch(); } });
  };
  const runWalkForward = () => {
    if (!canRun || walkMutation.isPending) return;
    const days = Math.max(30, Math.floor((new Date(form.toDate) - new Date(form.fromDate)) / 86400000));
    const validationWindowDays = Math.max(10, Math.floor(days * 0.2));
    const trainingWindowDays = Math.max(20, Math.floor(days * 0.5));
    walkMutation.mutate({ ...payload(), trainingWindowDays, validationWindowDays, stepDays: validationWindowDays }, { onSuccess: setWalkForward });
  };
  const runHoldout = () => {
    if (!canRun || validationMutation.isPending) return;
    validationMutation.mutate({ ...payload(), outOfSamplePercent: 30 }, { onSuccess: (data) => { setValidationId(data.validation.id); validations.refetch(); } });
  };

  const model = { connected: !!apiKey, run, totalRuns, form, instrumentText, selectedInstrument, matches: { ...requestState(matches), refetch: () => matches.refetch() }, canRun,
    runMutation: requestState(runMutation), walkForward, walkMutation: requestState(walkMutation), validDates, validCapital, selectedRunId,
    equity: requestState(equity), selectedDetail, detail: requestState(detail), exportMutation: requestState(exportMutation),
    runs: requestState(runs), validationMutation: requestState(validationMutation), validationId,
    validations: requestState(validations), validationDetail: requestState(validationDetail), assumptionsOpen };
  const actions = { changeForm, setInstrumentText, selectInstrument, clearInstrument, submit, runWalkForward, runHoldout,
    setAssumptionsOpen, setSelectedRunId, setValidationId, exportCsv: () => exportMutation.mutate({ id: selectedRunId, symbol: run?.tradingSymbol }),
    retryDetail: () => detail.refetch(), refreshRuns: () => runs.refetch(), retryValidationDetail: () => validationDetail.refetch() };
  return <ExperienceBoundary pro={<ProBacktestView model={model} actions={actions} />} clear={<ClearBacktestView model={model} actions={actions} />} />;
}
