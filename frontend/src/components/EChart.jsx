import { useEffect, useRef } from 'react';
import * as echarts from 'echarts/core';
import { LineChart, BarChart, PieChart, ScatterChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent, MarkLineComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { useApp } from '../context/AppContext.jsx';
import { cn } from '../lib/cn.js';

echarts.use([LineChart, BarChart, PieChart, ScatterChart, GridComponent, TooltipComponent, LegendComponent, MarkLineComponent, CanvasRenderer]);

/**
 * Thin ECharts wrapper. `buildOption(palette)` is re-run on theme change so colours follow the
 * active theme. `onClick` receives the ECharts click params for drill-down navigation.
 */
export default function EChart({ buildOption, onClick, className, label }) {
  const ref = useRef(null);
  const chartRef = useRef(null);
  const { theme } = useApp();

  useEffect(() => {
    const chart = echarts.init(ref.current, null, { renderer: 'canvas' });
    chartRef.current = chart;
    const ro = new ResizeObserver(() => chart.resize());
    ro.observe(ref.current);
    return () => {
      ro.disconnect();
      chart.dispose();
      chartRef.current = null;
    };
  }, []);

  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    chart.setOption(buildOption(), true);
  }, [buildOption, theme]);

  useEffect(() => {
    const chart = chartRef.current;
    if (!chart || !onClick) return undefined;
    chart.on('click', onClick);
    return () => chart.off('click', onClick);
  }, [onClick]);

  return <div ref={ref} role="img" aria-label={label} className={cn('h-64 w-full', className)} />;
}
