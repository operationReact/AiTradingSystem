const inrFormatter = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const isNumeric = (n) => n !== null && n !== undefined && n !== '' && Number.isFinite(Number(n));

export const formatInr = (n) => (isNumeric(n) ? inrFormatter.format(Number(n)) : '—');

export const formatSignedInr = (n) => {
  if (!isNumeric(n)) return '—';
  const v = Number(n);
  return `${v > 0 ? '+' : ''}${inrFormatter.format(v)}`;
};

export const formatPct = (n, digits = 2) => (isNumeric(n) ? `${Number(n).toFixed(digits)}%` : '—');

export const formatInteger = (n) => (isNumeric(n) ? new Intl.NumberFormat('en-IN').format(Number(n)) : '—');

export const pnlTone = (n) => {
  if (!isNumeric(n) || Number(n) === 0) return 'text-muted';
  return Number(n) > 0 ? 'text-gain' : 'text-loss';
};

export function formatDateTime(value) {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString('en-IN', {
    timeZone: 'Asia/Kolkata',
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

/** Accepts a yyyy-MM-dd business date (market timezone). */
export function formatBusinessDate(value) {
  if (!value) return '—';
  const d = new Date(`${value}T00:00:00+05:30`);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short' });
}

export function ageSeconds(value) {
  if (!value) return Infinity;
  const t = typeof value === 'number' ? value : new Date(value).getTime();
  return Number.isFinite(t) ? (Date.now() - t) / 1000 : Infinity;
}

export function formatAge(seconds) {
  if (!Number.isFinite(seconds)) return 'never';
  if (seconds < 60) return `${Math.max(0, Math.round(seconds))}s ago`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m ago`;
  return `${Math.round(seconds / 3600)}h ago`;
}
