import { useCallback } from 'react';
import EChart from '../EChart.jsx';
import { EmptyState } from '../ui.jsx';
import { baseOption, chartPalette } from '../../lib/chart.js';

export default function EquityCurveChart({ points = [] }) {
  const buildOption = useCallback(() => {
    const p = chartPalette();
    return {
      ...baseOption(p),
      grid: { left: 12, right: 12, top: 16, bottom: 24, containLabel: true },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: points.map((point) => new Date(point.pointTime).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })),
        axisLabel: { color: p.muted, fontSize: 10 },
        axisLine: { lineStyle: { color: p.border } },
      },
      yAxis: {
        type: 'value',
        scale: true,
        axisLabel: { color: p.muted, fontSize: 10 },
        splitLine: { lineStyle: { color: p.grid } },
      },
      series: [{
        type: 'line',
        name: 'Equity',
        showSymbol: false,
        smooth: false,
        data: points.map((point) => Number(point.equity)),
        lineStyle: { width: 2, color: p.accent },
        areaStyle: { opacity: 0.08, color: p.accent },
      }],
    };
  }, [points]);

  if (!points.length) return <EmptyState title="No equity curve yet" description="Run or select a backtest to inspect its marked-to-market equity path." />;
  return <EChart buildOption={buildOption} label="Backtest equity curve" className="h-64" />;
}
