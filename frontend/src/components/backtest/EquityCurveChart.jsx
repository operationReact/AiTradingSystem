import { useCallback } from 'react';
import EChart from '../EChart.jsx';
import { EmptyState } from '../ui.jsx';

export default function EquityCurveChart({ points = [] }) {
  const buildOption = useCallback(() => ({
    grid: { left: 12, right: 12, top: 16, bottom: 24, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: points.map((point) => new Date(point.pointTime).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })),
      axisLabel: { color: 'rgb(var(--muted))', fontSize: 10 },
      axisLine: { lineStyle: { color: 'rgb(var(--border))' } },
    },
    yAxis: {
      type: 'value',
      scale: true,
      axisLabel: { color: 'rgb(var(--muted))', fontSize: 10 },
      splitLine: { lineStyle: { color: 'rgba(139,151,170,.12)' } },
    },
    series: [{
      type: 'line',
      name: 'Equity',
      showSymbol: false,
      smooth: false,
      data: points.map((point) => Number(point.equity)),
      lineStyle: { width: 2, color: 'rgb(var(--accent))' },
      areaStyle: { opacity: 0.08, color: 'rgb(var(--accent))' },
    }],
  }), [points]);

  if (!points.length) return <EmptyState title="No equity curve yet" description="Run or select a backtest to inspect its marked-to-market equity path." />;
  return <EChart buildOption={buildOption} label="Backtest equity curve" className="h-64" />;
}
