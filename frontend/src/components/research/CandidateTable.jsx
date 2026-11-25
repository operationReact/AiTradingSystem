import { Link } from 'react-router-dom';
import CandidateScoreBadge from './CandidateScoreBadge.jsx';
import CandidateReasonList from './CandidateReasonList.jsx';

export default function CandidateTable({ candidates = [] }) {
  if (!candidates.length) return <div className="rounded-lg border border-dashed border-border p-6 text-sm text-muted">No candidates for this scan.</div>;
  return (
    <div className="overflow-x-auto rounded-lg border border-border bg-surface">
      <table className="min-w-full text-left text-sm">
        <thead className="border-b border-border bg-surface-2 text-xs uppercase tracking-wide text-muted">
          <tr><th className="px-3 py-2">Symbol</th><th className="px-3 py-2">Strategy</th><th className="px-3 py-2">Score</th><th className="px-3 py-2">Entry</th><th className="px-3 py-2">Stop</th><th className="px-3 py-2">Target</th><th className="px-3 py-2">Reasons</th></tr>
        </thead>
        <tbody>
          {candidates.map((candidate) => (
            <tr key={candidate.candidateFingerprint ?? `${candidate.instrumentId}-${candidate.strategy}-${candidate.signalCandleTime}`} className="border-b border-border/70 last:border-0">
              <td className="whitespace-nowrap px-3 py-3 font-medium text-text"><div>{candidate.candidateId ? <Link className="text-accent underline" to={`/research/candidates/${candidate.candidateId}`}>{candidate.tradingSymbol}</Link> : candidate.tradingSymbol}</div><div className="text-[11px] text-muted">{candidate.exchange} · {candidate.interval}</div></td>
              <td className="whitespace-nowrap px-3 py-3 text-text">{candidate.strategy}</td>
              <td className="px-3 py-3"><CandidateScoreBadge score={candidate.score} /></td>
              <td className="whitespace-nowrap px-3 py-3 text-text">{candidate.referencePrice}</td>
              <td className="whitespace-nowrap px-3 py-3 text-text">{candidate.suggestedStop}</td>
              <td className="whitespace-nowrap px-3 py-3 text-text">{candidate.suggestedTarget}</td>
              <td className="min-w-72 px-3 py-3"><CandidateReasonList reasons={candidate.reasons} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
