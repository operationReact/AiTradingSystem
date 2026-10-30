import { formatInr, formatPct } from './format.js';

const PAGE_SIZE = 10;

export const POLICY_FIELDS = [
  ['riskPerTradePercent', 'Risk per trade', '%'],
  ['maxDailyDrawdownPercent', 'Max daily drawdown', '%'],
  ['maxOpenPositions', 'Max open positions', 'count'],
  ['minRewardRiskRatio', 'Minimum reward:risk', 'ratio'],
  ['maxOrderNotional', 'Max order notional', 'money'],
  ['maxGrossExposurePercent', 'Max gross exposure', '%'],
  ['maxSymbolExposurePercent', 'Max symbol exposure', '%'],
  ['maxTradesPerDay', 'Max trades/day', 'count'],
  ['signalMaxAgeMinutes', 'Signal max age', 'minutes'],
  ['marketStateMaxAgeSeconds', 'Market-state max age', 'seconds'],
];

export function toNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

export function policyDisplay(value, unit) {
  if (value == null) return '—';
  if (unit === '%') return formatPct(Number(value));
  if (unit === 'money') return formatInr(Number(value));
  if (unit === 'ratio') return `${Number(value).toFixed(2)} R`;
  if (unit === 'minutes') return `${value} min`;
  if (unit === 'seconds') return `${value} sec`;
  return String(value);
}

export function utilization(value, limit) {
  const observed = Math.max(0, Number(value || 0));
  const maximum = Number(limit || 0);
  if (!Number.isFinite(maximum) || maximum <= 0) return 0;
  return Math.min(100, (observed / maximum) * 100);
}

