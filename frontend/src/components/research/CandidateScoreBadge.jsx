export default function CandidateScoreBadge({ score }) {
  const value = Number(score ?? 0);
  return <span className="inline-flex rounded-full border border-border bg-surface-2 px-2 py-0.5 text-xs font-semibold text-text">{Number.isFinite(value) ? value.toFixed(2) : '—'}</span>;
}
