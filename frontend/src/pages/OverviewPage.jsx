import { useMemo, useState } from 'react';
import { useQueryClient, useIsFetching } from '@tanstack/react-query';
import { useApp } from '../context/AppContext.jsx';
import { useDashboardSummary, useEquityCurve, useOrders, usePortfolio, useRiskConfig, useSignals } from '../hooks/useApi.js';
import { useBrokerStatusController, useNewsIntelligenceController, useRssNewsController } from '../hooks/useDashboardWidgets.js';
import { requestState } from '../lib/viewModel.js';
import { RANGES, WIDGETS } from '../lib/dashboardPreferences.js';
import { formatAge, ageSeconds } from '../lib/format.js';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import ProOverviewView from '../components/dashboard/pro/ProOverviewView.jsx';
import ClearOverviewView from '../components/dashboard/clear/ClearOverviewView.jsx';
const RANGE_KEY = 'sf-dashboard-range';
const WIDGETS_KEY = 'sf-dashboard-hidden';

function readJson(key, fallback) {
  try {
    return JSON.parse(localStorage.getItem(key)) ?? fallback;
  } catch {
    return fallback;
  }
}

export default function OverviewPage() {
  const { apiKey } = useApp();
  const qc = useQueryClient();
  const fetching = useIsFetching({ queryKey: ['dashboard'] }) + useIsFetching({ queryKey: ['paper'] });
  const [days, setDays] = useState(() => {
    let saved;
    try { saved = Number(localStorage.getItem(RANGE_KEY)); } catch { saved = NaN; }
    return RANGES.some((r) => r.value === saved) ? saved : 30;
  });
  const [hidden, setHidden] = useState(() => {
    const stored = readJson(WIDGETS_KEY, []);
    return new Set(Array.isArray(stored) ? stored.filter((id) => WIDGETS.some((widget) => widget.id === id)) : []);
  });
  const [showSettings, setShowSettings] = useState(false);

  const summary = useDashboardSummary();
  const equity = useEquityCurve(days);
  const portfolio = usePortfolio();
  const orders = useOrders({ page: 0, size: 8 });
  const signals = useSignals();
  const riskConfig = useRiskConfig();

  const show = (id) => !hidden.has(id);
  const toggleWidget = (id) => {
    setHidden((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      try { localStorage.setItem(WIDGETS_KEY, JSON.stringify([...next])); } catch { /* Preferences remain usable in memory when storage is unavailable. */ }
      return next;
    });
  };
  const changeRange = (value) => {
    const selected = Number(value);
    if (!RANGES.some((range) => range.value === selected)) return;
    setDays(selected);
    try { localStorage.setItem(RANGE_KEY, String(selected)); } catch { /* Keep the active range even without persistent storage. */ }
  };
  const refresh = () => qc.invalidateQueries({ predicate: (q) => ['dashboard', 'paper', 'signals', 'risk'].includes(q.queryKey[0]) });

  const curve = useMemo(() => equity.data ?? [], [equity.data]);
  const freshness = summary.dataUpdatedAt ? formatAge(ageSeconds(summary.dataUpdatedAt)) : 'not yet loaded';

  const broker = useBrokerStatusController(show('providers'));
  const news = useNewsIntelligenceController(show('news'));
  const rss = useRssNewsController(show('provenance-news'));
  const project = query => ({ ...requestState(query), refetch: () => query.refetch() });
  const model = { connected: !!apiKey, fetching, days, hidden, showSettings, summary: project(summary), equity: project(equity),
    portfolio: project(portfolio), orders: project(orders), signals: project(signals), riskConfig: project(riskConfig), curve, freshness,
    broker, news, rss };
  const actions = { show, toggleWidget, changeRange, setShowSettings, refresh };
  return <ExperienceBoundary pro={<ProOverviewView model={model} actions={actions} />} clear={<ClearOverviewView model={model} actions={actions} />} />;
}
