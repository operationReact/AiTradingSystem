import { useCallback, useMemo } from 'react';
import EChart from '../EChart.jsx';
import { EmptyState } from '../ui.jsx';
import { baseOption, chartPalette } from '../../lib/chart.js';

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
  const buildOption = useCallback(() => {
    const p = chartPalette();
    return {
      ...baseOption(p),
      grid: { left: 12, right: 12, top: 16, bottom: 24, containLabel: true },
      tooltip: { trigger: 'axis', valueFormatter: (value) => `${Number(value).toFixed(2)}%` },
      xAxis: { type: 'category', boundaryGap: false, data: rows.map(([date]) => date), axisLabel: { color: p.muted, fontSize: 10 }, axisLine: { lineStyle: { color: p.border } } },
      yAxis: { type: 'value', max: 0, axisLabel: { color: p.muted, fontSize: 10, formatter: '{value}%' }, splitLine: { lineStyle: { color: p.grid } } },
      series: [{ type: 'line', showSymbol: false, data: rows.map(([, value]) => value), lineStyle: { width: 2, color: p.loss }, areaStyle: { opacity: 0.08, color: p.loss } }],
    };
  }, [rows]);
  if (!points.length) return <EmptyState title="No drawdown series yet" description="Drawdown is derived from the selected run's equity curve." />;
  return <EChart buildOption={buildOption} label="Backtest drawdown chart" className="h-64" />;
}
