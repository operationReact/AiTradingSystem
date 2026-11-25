import { useEffect, useState } from 'react';
import { ApiError } from '../lib/api.js';
import { formatInr } from '../lib/format.js';
import { useSimulateTrade } from '../hooks/useApi.js';
import { Badge, Button, Modal, Notice } from './ui.jsx';

const EMPTY = { symbol: '', side: 'BUY', quantity: '1', entryPrice: '', stopLoss: '', target: '' };

function fromPrefill(prefill) {
  if (!prefill) return EMPTY;
  const side = String(prefill.action ?? '').toUpperCase().includes('SELL') ? 'SELL' : 'BUY';
  return {
    symbol: prefill.symbol ?? '',
    side,
    quantity: '1',
    entryPrice: prefill.referencePrice != null ? String(prefill.referencePrice) : '',
    stopLoss: prefill.stopLoss != null ? String(prefill.stopLoss) : '',
    target: prefill.target != null ? String(prefill.target) : '',
  };
}

/** Client-side checks mirror the backend's rules. The backend remains the authority. */
export function validateSimulate(form) {
  const errors = {};
  if (!/^[A-Z0-9&-]{1,20}$/.test(form.symbol)) errors.symbol = 'Use 1–20 uppercase letters, digits, & or -';
  const qty = Number(form.quantity);
  if (!Number.isInteger(qty) || qty < 1 || qty > 10000) errors.quantity = 'Quantity must be a whole number from 1 to 10,000';
  const entry = Number(form.entryPrice);
  if (!(entry >= 0.01)) errors.entryPrice = 'Entry price must be at least 0.01';
  const stop = form.stopLoss === '' ? null : Number(form.stopLoss);
  const target = form.target === '' ? null : Number(form.target);
  if (form.side === 'BUY') {
    if (stop === null) errors.stopLoss = 'BUY orders require a stop loss';
    else if (stop >= entry) errors.stopLoss = 'Stop loss must be below entry for BUY';
    if (target === null) errors.target = 'BUY orders require a target';
    else if (target <= entry) errors.target = 'Target must be above entry for BUY';
  }
  return errors;
}

function Field({ label, error, children }) {
  return (
    <label className="flex flex-col gap-1 text-xs text-muted">
      {label}
      {children}
      {error && <span className="text-loss">{error}</span>}
    </label>
  );
}

const inputClass =
  'h-9 rounded-md border border-border bg-surface-2 px-2.5 text-sm text-text focus:border-accent focus:outline-none';

/**
 * Paper-only order entry. One Idempotency-Key is generated per dialog session and reused on
 * retries, so a double-click or network retry cannot create a duplicate order.
 */
export default function SimulateTradeDialog({ open, onOpenChange, prefill, source }) {
  const [form, setForm] = useState(EMPTY);
  const [errors, setErrors] = useState({});
  const [result, setResult] = useState(null);
  const [failure, setFailure] = useState(null);
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());
  const [signalAt, setSignalAt] = useState(() => new Date().toISOString());
  const mutation = useSimulateTrade();

  // Parents must pass a stable `prefill` (state, not an inline object) so this does not reset the form on every render.
  useEffect(() => {
    if (open) {
      setForm(fromPrefill(prefill));
      setErrors({});
      setResult(null);
      setFailure(null);
      setSignalAt(new Date().toISOString());
      setIdempotencyKey(crypto.randomUUID());
    }
  }, [open, prefill]);

  const update = (key) => (e) => setForm((f) => ({ ...f, [key]: key === 'symbol' ? e.target.value.toUpperCase() : e.target.value }));

  const submit = async () => {
    const found = validateSimulate(form);
    setErrors(found);
    if (Object.keys(found).length) return;
    const body = {
      symbol: form.symbol,
      side: form.side,
      quantity: Number(form.quantity),
      entryPrice: Number(form.entryPrice),
      stopLoss: form.stopLoss === '' ? null : Number(form.stopLoss),
      target: form.target === '' ? null : Number(form.target),
      signalGeneratedAt: prefill?.createdAt ?? signalAt,
    };
    setFailure(null);
    try {
      const res = await mutation.mutateAsync({ idempotencyKey, body });
      setResult(res);
    } catch (err) {
      if (err instanceof ApiError) setFailure({ message: err.message, reasons: err.body?.reasonCodes ?? [], status: err.status });
      else setFailure({ message: err.message, reasons: [], status: 0 });
    }
  };

  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title="Simulate paper order"
      description="Paper execution only. No order is sent to a broker."
      footer={
        result ? (
          <Button variant="primary" onClick={() => onOpenChange(false)}>Done</Button>
        ) : (
          <>
            <Button onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button variant="primary" loading={mutation.isPending} onClick={submit}>Submit paper order</Button>
          </>
        )
      }
    >
      {source && <p className="mb-3 text-xs text-muted">Prefilled from signal {source}. Review prices before submitting.</p>}

      {result ? (
        <div className="flex flex-col gap-3 text-sm">
          <div className="flex items-center gap-2">
            <Badge tone={result.order.status === 'FILLED' ? 'gain' : 'accent'}>{result.order.status}</Badge>
            {result.replayed && <Badge tone="neutral">Replayed</Badge>}
          </div>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-xs">
            <dt className="text-muted">Order</dt>
            <dd className="num text-text">{result.order.symbol} {result.order.side} × {result.order.quantity}</dd>
            <dt className="text-muted">Entry</dt>
            <dd className="num text-text">{formatInr(result.order.entryPrice)}</dd>
            {result.fill && (
              <>
                <dt className="text-muted">Fill price</dt>
                <dd className="num text-text">{formatInr(result.fill.fillPrice)}</dd>
                <dt className="text-muted">Fees + slippage</dt>
                <dd className="num text-text">{formatInr(Number(result.fill.feeAmount) + Number(result.fill.slippageAmount))}</dd>
              </>
            )}
          </dl>
          <p className="text-xs text-muted">Decision reference: {result.decision?.id}</p>
        </div>
      ) : (
        <form
          className="grid grid-cols-2 gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          <Field label="Symbol" error={errors.symbol}>
            <input className={inputClass} value={form.symbol} onChange={update('symbol')} maxLength={20} autoFocus />
          </Field>
          <Field label="Side">
            <select className={inputClass} value={form.side} onChange={update('side')}>
              <option value="BUY">BUY</option>
              <option value="SELL">SELL</option>
            </select>
          </Field>
          <Field label="Quantity" error={errors.quantity}>
            <input className={inputClass} type="number" min={1} max={10000} value={form.quantity} onChange={update('quantity')} />
          </Field>
          <Field label="Entry price (₹)" error={errors.entryPrice}>
            <input className={inputClass} type="number" step="0.01" min={0.01} value={form.entryPrice} onChange={update('entryPrice')} />
          </Field>
          <Field label="Stop loss (₹)" error={errors.stopLoss}>
            <input className={inputClass} type="number" step="0.01" min={0.01} value={form.stopLoss} onChange={update('stopLoss')} />
          </Field>
          <Field label="Target (₹)" error={errors.target}>
            <input className={inputClass} type="number" step="0.01" min={0.01} value={form.target} onChange={update('target')} />
          </Field>
          <p className="col-span-2 text-xs text-muted">
            Signal time: {prefill?.createdAt ? 'from signal' : 'set to now (manual entry, will be checked for staleness)'}
          </p>
          {failure && (
            <div className="col-span-2">
              <Notice tone="loss" title={failure.status === 422 ? 'Risk policy rejected this order' : 'Order not submitted'}>
                {failure.message}
                {failure.reasons.length > 0 && <ul className="mt-1 list-inside list-disc">{failure.reasons.map((r) => <li key={r}>{r}</li>)}</ul>}
              </Notice>
            </div>
          )}
        </form>
      )}
    </Modal>
  );
}
