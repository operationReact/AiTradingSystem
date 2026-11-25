export default function CandidateReasonList({ reasons = [] }) {
  if (!reasons?.length) return <span className="text-xs text-muted">No reasons</span>;
  return <div className="flex flex-wrap gap-1">{reasons.map((reason, index) => <span key={`${reason}-${index}`} className="rounded bg-surface-2 px-2 py-0.5 text-[11px] text-muted">{reason}</span>)}</div>;
}
