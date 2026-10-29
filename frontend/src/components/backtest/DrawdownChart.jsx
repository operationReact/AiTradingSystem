import { useCallback, useMemo } from 'react';
import EChart from '../EChart.jsx';
import { EmptyState } from '../ui.jsx';

export default function DrawdownChart({ points = [] }) {
  const rows = useMemo(() => {
    let peak = 0;
    return points.map((point) => {
      const equity = Number(point.equity || 0);
      peak = Math.max(peak, equity);
      const drawdown = peak > 0 ? ((equity - peak) / peak) * 100 : 0;
      return [new Date(point.pointTime).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' }), drawdown];
    });
  }, [points]);
  const buildOption = useCallback(() => ({
    grid: { left: 12, right: 12, top: 16, bottom: 24, containLabel: true },
    tooltip: { trigger: 'axis', valueFormatter: (value) => `${Number(value).toFixed(2)}%` },
    xAxis: { type: 'category', boundaryGap: false, data: rows.map(([date]) => date), axisLabel: { color: 'rgb(var(--muted))', fontSize: 10 }, axisLine: { lineStyle: { color: 'rgb(var(--border))' } } },
    yAxis: { type: 'value', max: 0, axisLabel: { color: 'rgb(var(--muted))', fontSize: 10, formatter: '{value}%' }, splitLine: { lineStyle: { color: 'rgba(139,151,170,.12)' } } },
    series: [{ type: 'line', showSymbol: false, data: rows.map(([, value]) => value), lineStyle: { width: 2, color: 'rgb(var(--loss))' }, areaStyle: { opacity: 0.08, color: 'rgb(var(--loss))' } }],
  }), [rows]);
  if (!points.length) return <EmptyState title="No drawdown series yet" description="Drawdown is derived from the selected run's equity curve." />;
  return <EChart buildOption={buildOption} label="Backtest drawdown chart" className="h-64" />;
}
