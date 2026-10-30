import { useMemo, useState } from 'react';
import { useEvaluateStrategy, useInstrumentSearch, useSignals } from '../hooks/useApi.js';
import { useApp } from '../context/AppContext.jsx';
import { requestState } from '../lib/viewModel.js';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import ProSignalsView from '../components/signals/pro/ProSignalsView.jsx';
import ClearSignalsView from '../components/signals/clear/ClearSignalsView.jsx';
import SimulateTradeDialog from '../components/SimulateTradeDialog.jsx';

export default function SignalsPage() {
  const { apiKey } = useApp();
  const [draftQuery, setDraftQuery] = useState('');
  const [criteria, setCriteria] = useState('');
  const [selectedId, setSelectedId] = useState('');
  const [interval, setInterval] = useState('FIVE_MINUTE');
  const [dialogOpen, setDialogOpen] = useState(false);
  const [prefill, setPrefill] = useState(null);

  const instruments = useInstrumentSearch({
    q: criteria,
    exchange: 'NSE',
    type: 'EQ',
    page: 0,
    size: 50,
    enabled: !!criteria,
  });
  const evaluateMutation = useEvaluateStrategy();
  const history = useSignals(25);
  const selectedInstrument = useMemo(() => {
    const list = instruments.data?.content || [];
    // A selected instrument must remain in the current result set; never silently switch identity.
    return selectedId ? list.find((item) => String(item.id) === String(selectedId)) || null : list[0] || null;
  }, [instruments.data, selectedId]);
  const evaluation = apiKey && !instruments.isPending && !instruments.isError && evaluateMutation.data
    && Number(evaluateMutation.data.instrumentId) === Number(selectedInstrument?.id)
    && evaluateMutation.data.interval === interval
      ? evaluateMutation.data : null;

  const submitSearch = (event) => {
    event.preventDefault();
    setCriteria(draftQuery.trim().toUpperCase());
    setSelectedId('');
    evaluateMutation.reset();
  };

  const evaluate = () => {
    if (!selectedInstrument || !apiKey || instruments.isPending || instruments.isError || evaluateMutation.isPending) return;
    evaluateMutation.mutate({ instrumentId: selectedInstrument.id, interval });
  };

  const openSimulate = (signal) => {
    if (!apiKey || !evaluation || !signal) return;
    setPrefill(signal);
    setDialogOpen(true);
  };

  const model = { connected: !!apiKey, draftQuery, selectedInstrument, interval, instruments: requestState(instruments),
    evaluateMutation: requestState(evaluateMutation), history: requestState(history), evaluation };
  const actions = { setDraftQuery, submitSearch, onInstrumentChange: value => { setSelectedId(value); evaluateMutation.reset(); },
    onIntervalChange: value => { setInterval(value); evaluateMutation.reset(); }, evaluate, openSimulate,
    retryInstruments: () => instruments.refetch(), retryHistory: () => history.refetch() };
  return <>
    <ExperienceBoundary pro={<ProSignalsView model={model} actions={actions} />} clear={<ClearSignalsView model={model} actions={actions} />} />
      <SimulateTradeDialog open={!!apiKey && dialogOpen} onOpenChange={setDialogOpen} prefill={prefill}
        source={prefill ? `${prefill.symbol} · ${prefill.strategy}` : null} />
  </>;
}
