import { useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import EChart from '../EChart.jsx';
import { EmptyState } from '../ui.jsx';
import { baseOption, chartPalette } from '../../lib/chart.js';
import { formatBusinessDate } from '../../lib/format.js';

const axisCommon = (p) => ({
  axisLine: { lineStyle: { color: p.grid } },
  axisLabel: { color: p.muted, fontSize: 11 },
  splitLine: { lineStyle: { color: p.grid } },
});

const compactNumber = (v) => new Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 }).format(v);

export function EquityChart({ points }) {
  const navigate = useNavigate();
  const build = useCallback(() => {
    const p = chartPalette();
    const axis = axisCommon(p);
    return {
      ...baseOption(p),
      tooltip: { ...baseOption(p).tooltip, trigger: 'axis', valueFormatter: (v) => compactNumber(v) },
      xAxis: { type: 'category', data: points.map((x) => x.date), boundaryGap: false, ...axis, axisLabel: { ...axis.axisLabel, formatter: formatBusinessDate } },
      yAxis: { type: 'value', scale: true, ...axis, axisLabel: { ...axis.axisLabel, formatter: compactNumber } },
      series: [
        {
          name: 'Realized equity',
          type: 'line',
          smooth: 0.2,
          showSymbol: false,
          data: points.map((x) => Number(x.realizedEquity)),
          lineStyle: { color: p.accent, width: 2 },
          areaStyle: { color: p.accentSoft },
          itemStyle: { color: p.accent },
        },
      ],
    };
  }, [points]);

  const onClick = useCallback(() => navigate('/trades'), [navigate]);

  if (points.length === 0) return <EmptyState title="No equity history yet" description="Equity appears after the first paper fill is recorded." />;
  return <EChart buildOption={build} onClick={onClick} label="Realized equity curve" />;
}

export function PnlChart({ points }) {
  const build = useCallback(() => {
    const p = chartPalette();
    const axis = axisCommon(p);
    return {
      ...baseOption(p),
      tooltip: { ...baseOption(p).tooltip, trigger: 'axis' },
      legend: { top: 0, right: 0, textStyle: { color: p.muted, fontSize: 11 }, itemHeight: 8 },
      xAxis: { type: 'category', data: points.map((x) => x.date), ...axis, axisLabel: { ...axis.axisLabel, formatter: formatBusinessDate } },
      yAxis: [
        { type: 'value', ...axis, axisLabel: { ...axis.axisLabel, formatter: compactNumber } },
        { type: 'value', ...axis, splitLine: { show: false }, axisLabel: { ...axis.axisLabel, formatter: compactNumber } },
      ],
      series: [
        {
          name: 'Daily realized P&L',
          type: 'bar',
          barMaxWidth: 14,
          data: points.map((x) => {
            const v = Number(x.realizedPnl);
            return { value: v, itemStyle: { color: v >= 0 ? p.gain : p.loss } };
          }),
        },
        {
          name: 'Cumulative',
          type: 'line',
          yAxisIndex: 1,
          showSymbol: false,
          data: points.map((x) => Number(x.cumulativeRealizedPnl)),
          lineStyle: { color: p.warn, width: 2 },
          itemStyle: { color: p.warn },
        },
      ],
    };
  }, [points]);

  if (points.length === 0) return <EmptyState title="No realized P&L in this range" />;
  return <EChart buildOption={build} label="Daily and cumulative realized P&L" />;
}

/** Drawdown from the running peak of realized equity, in percent. */
export function DrawdownChart({ points }) {
  const drawdown = useMemo(() => {
    let peak = -Infinity;
    return points.map((x) => {
      const eq = Number(x.realizedEquity);
      peak = Math.max(peak, eq);
      return peak > 0 ? ((eq - peak) / peak) * 100 : 0;
    });
  }, [points]);

  const build = useCallback(() => {
    const p = chartPalette();
    const axis = axisCommon(p);
    return {
      ...baseOption(p),
      tooltip: { ...baseOption(p).tooltip, trigger: 'axis', valueFormatter: (v) => `${Number(v).toFixed(2)}%` },
      xAxis: { type: 'category', data: points.map((x) => x.date), boundaryGap: false, ...axis, axisLabel: { ...axis.axisLabel, formatter: formatBusinessDate } },
      yAxis: { type: 'value', max: 0, ...axis, axisLabel: { ...axis.axisLabel, formatter: (v) => `${v}%` } },
      series: [{ name: 'Drawdown', type: 'line', showSymbol: false, data: drawdown, lineStyle: { color: p.loss, width: 1.5 }, areaStyle: { color: p.lossSoft } }],
    };
  }, [points, drawdown]);

  if (points.length === 0) return <EmptyState title="No drawdown data yet" />;
  return <EChart buildOption={build} label="Drawdown from peak" />;
}

/** Allocation across open positions and cash. Position values use the last paper fill as mark. */
export function AllocationChart({ positions, cash }) {
  const navigate = useNavigate();
  const build = useCallback(() => {
    const p = chartPalette();
    const palette = [p.accent, p.gain, p.warn, '#a78bfa', '#38bdf8', '#f472b6', p.loss];
    const data = positions
      .map((pos, i) => ({ name: pos.symbol, value: Number(pos.marketValue), itemStyle: { color: palette[i % palette.length] } }))
      .filter((d) => d.value > 0);
    const cashValue = Number(cash);
    if (cashValue > 0) data.push({ name: 'Cash', value: cashValue, itemStyle: { color: p.grid } });
    return {
      ...baseOption(p),
      tooltip: { ...baseOption(p).tooltip, trigger: 'item', formatter: '{b}: {d}%' },
      legend: { bottom: 0, textStyle: { color: p.muted, fontSize: 11 }, itemHeight: 8, type: 'scroll' },
      series: [{ type: 'pie', radius: ['55%', '78%'], center: ['50%', '45%'], label: { show: false }, data }],
    };
  }, [positions, cash]);

  const onClick = useCallback(
    (params) => {
      if (params?.name && params.name !== 'Cash') navigate(`/trades?symbol=${encodeURIComponent(params.name)}`);
    },
    [navigate],
  );

  if (positions.length === 0 && !(Number(cash) > 0)) return <EmptyState title="No allocation to show" />;
  return <EChart buildOption={build} onClick={onClick} label="Portfolio allocation" />;
}
