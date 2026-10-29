import { forwardRef, useMemo, useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { AlertTriangle, ChevronDown, ChevronLeft, ChevronRight, ChevronUp, Loader2, RefreshCw, X } from 'lucide-react';
import { cn } from '../lib/cn.js';

export function Panel({ title, subtitle, actions, children, className, bodyClassName }) {
  return (
    <section className={cn('experience-panel flex min-w-0 flex-col rounded-lg border border-border bg-surface', className)}>
      {(title || actions) && (
        <header className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-2.5">
          <div className="min-w-0">
            {title && <h2 className="truncate text-sm font-semibold text-text">{title}</h2>}
            {subtitle && <p className="truncate text-xs text-muted">{subtitle}</p>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={cn('min-w-0 flex-1 p-4', bodyClassName)}>{children}</div>
    </section>
  );
}

const variants = {
  primary: 'bg-accent text-[rgb(var(--accent-contrast))] hover:brightness-110',
  secondary: 'border border-border bg-surface-2 text-text hover:bg-border/60',
  ghost: 'text-muted hover:bg-surface-2 hover:text-text',
  danger: 'bg-loss text-[rgb(var(--loss-contrast))] hover:brightness-110',
};

export const Button = forwardRef(function Button(
  { variant = 'secondary', size = 'md', loading = false, icon: Icon, className, children, disabled, ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      type="button"
      disabled={disabled || loading}
      className={cn(
        'inline-flex items-center justify-center gap-1.5 rounded-md font-medium transition-colors duration-150',
        'disabled:cursor-not-allowed disabled:opacity-50',
        size === 'sm' ? 'min-h-10 px-2.5 text-xs sm:min-h-7' : 'min-h-11 px-3.5 text-sm sm:min-h-9',
        variants[variant],
        className,
      )}
      {...props}
    >
      {loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden /> : Icon && <Icon className="h-3.5 w-3.5" aria-hidden />}
      {children}
    </button>
  );
});

const badgeTones = {
  neutral: 'bg-surface-2 text-muted border-border',
  gain: 'bg-gain/10 text-gain border-gain/30',
  loss: 'bg-loss/10 text-loss border-loss/30',
  warn: 'bg-warn/10 text-warn border-warn/30',
  accent: 'bg-accent/10 text-accent border-accent/30',
};

export function Badge({ tone = 'neutral', children, title }) {
  return (
    <span
      title={title}
      className={cn('inline-flex items-center rounded border px-1.5 py-0.5 text-[11px] font-medium uppercase tracking-wide', badgeTones[tone])}
    >
      {children}
    </span>
  );
}

export function Skeleton({ className }) {
  return <div aria-hidden className={cn('animate-pulse rounded bg-surface-2', className)} />;
}

export function EmptyState({ title, description, action }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-8 text-center">
      <p className="text-sm font-medium text-text">{title}</p>
      {description && <p className="max-w-sm text-xs text-muted">{description}</p>}
      {action}
    </div>
  );
}

export function Notice({ tone = 'warn', title, children, action }) {
  const tones = {
    warn: 'border-warn/40 bg-warn/5 text-warn',
    loss: 'border-loss/40 bg-loss/5 text-loss',
    neutral: 'border-border bg-surface-2 text-muted',
  };
  return (
    <div role="status" className={cn('flex items-start gap-3 rounded-md border px-3 py-2.5 text-xs', tones[tone])}>
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <div className="min-w-0 flex-1 space-y-0.5">
        {title && <p className="font-semibold">{title}</p>}
        <div className="text-muted">{children}</div>
      </div>
      {action}
    </div>
  );
}

export function ErrorState({ error, onRetry }) {
  return (
    <Notice tone="loss" title="Could not load data" action={onRetry && <Button size="sm" icon={RefreshCw} onClick={onRetry}>Retry</Button>}>
      {error?.message || 'Unknown error'}
    </Notice>
  );
}

export function KpiCard({ label, value, hint, tone, loading }) {
  return (
    <div className="flex min-w-0 flex-col gap-1 rounded-lg border border-border bg-surface px-4 py-3">
      <span className="truncate text-[11px] font-medium uppercase tracking-wider text-muted">{label}</span>
      {loading ? (
        <Skeleton className="h-6 w-28" />
      ) : (
        <span className={cn('num truncate text-xl font-semibold text-text', tone)}>{value}</span>
      )}
      {hint && <span className="truncate text-xs text-muted">{hint}</span>}
    </div>
  );
}

export function SegmentedControl({ label, options, value, onChange }) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex rounded-md border border-border bg-surface-2 p-0.5">
      {options.map((opt) => {
        const active = opt.value === value;
        return (
          <button
            key={opt.value}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(opt.value)}
            className={cn(
              'min-h-10 rounded px-2.5 text-xs font-medium transition-colors sm:min-h-6',
              active ? 'bg-accent text-[rgb(var(--accent-contrast))]' : 'text-muted hover:text-text',
            )}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

/** Generic sortable, paginated table. Sorting and paging are client-side over the rows provided. */
export function DataTable({ columns, rows, rowKey, pageSize = 10, onRowClick, empty, minWidth = 560 }) {
  const [sort, setSort] = useState({ key: null, dir: 'asc' });
  const [page, setPage] = useState(0);

  const sorted = useMemo(() => {
    if (!sort.key) return rows;
    const col = columns.find((c) => c.key === sort.key);
    if (!col) return rows;
    const get = col.sortValue || ((r) => r[col.key]);
    const factor = sort.dir === 'asc' ? 1 : -1;
    return [...rows].sort((a, b) => {
      const va = get(a);
      const vb = get(b);
      if (va === vb) return 0;
      if (va === null || va === undefined) return 1;
      if (vb === null || vb === undefined) return -1;
      return (va > vb ? 1 : -1) * factor;
    });
  }, [rows, sort, columns]);

  const pageCount = Math.max(1, Math.ceil(sorted.length / pageSize));
  const safePage = Math.min(page, pageCount - 1);
  const visible = sorted.slice(safePage * pageSize, safePage * pageSize + pageSize);

  const toggleSort = (key) => {
    setSort((s) => (s.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' }));
    setPage(0);
  };

  return (
    <div className="min-w-0 overflow-x-auto" tabIndex={0} aria-label="Scrollable data table">
      <table className="w-full border-collapse text-xs" style={{ minWidth }}>
        <thead>
          <tr className="border-b border-border text-left text-muted">
            {columns.map((col) => (
              <th key={col.key} className={cn('px-3 py-2 font-medium', col.align === 'right' && 'text-right')}>
                {col.sortable === false ? col.header : (
                  <button
                    type="button"
                    onClick={() => toggleSort(col.key)}
                    className={cn('inline-flex items-center gap-1 hover:text-text', col.align === 'right' && 'float-right')}
                  >
                    {col.header}
                    {sort.key === col.key && (sort.dir === 'asc' ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />)}
                  </button>
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {visible.length === 0 ? (
            <tr><td colSpan={columns.length} className="px-3 py-8 text-center text-muted">{empty || 'No data'}</td></tr>
          ) : visible.map((row) => (
            <tr
              key={rowKey(row)}
              className={cn('border-b border-border/70 last:border-b-0', onRowClick && 'cursor-pointer hover:bg-surface-2')}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
            >
              {columns.map((col) => (
                <td key={col.key} className={cn('px-3 py-2.5 text-text', col.align === 'right' && 'text-right')}>
                  {col.cell ? col.cell(row) : row[col.key]}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {pageCount > 1 && (
        <div className="flex items-center justify-between border-t border-border px-3 py-2 text-xs text-muted">
          <span>Page {safePage + 1} of {pageCount}</span>
          <div className="flex gap-1">
            <Button size="sm" variant="ghost" disabled={safePage === 0} onClick={() => setPage((p) => Math.max(0, p - 1))} icon={ChevronLeft}>Previous</Button>
            <Button size="sm" variant="ghost" disabled={safePage + 1 >= pageCount} onClick={() => setPage((p) => Math.min(pageCount - 1, p + 1))} icon={ChevronRight}>Next</Button>
          </div>
        </div>
      )}
    </div>
  );
}

export function Modal({ open, onOpenChange, title, description, children, footer }) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/60" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[calc(100%-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-lg border border-border bg-surface shadow-2xl">
          <div className="flex items-start justify-between gap-3 border-b border-border px-4 py-3">
            <div>
              <Dialog.Title className="text-sm font-semibold text-text">{title}</Dialog.Title>
              {description && <Dialog.Description className="mt-0.5 text-xs text-muted">{description}</Dialog.Description>}
            </div>
            <Dialog.Close asChild>
              <button type="button" className="rounded p-1 text-muted hover:bg-surface-2 hover:text-text" aria-label="Close dialog">
                <X className="h-4 w-4" />
              </button>
            </Dialog.Close>
          </div>
          <div className="p-4">{children}</div>
          {footer && <div className="flex justify-end gap-2 border-t border-border px-4 py-3">{footer}</div>}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
