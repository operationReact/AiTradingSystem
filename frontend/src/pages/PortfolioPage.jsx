import { useApp } from '../context/AppContext.jsx';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import ProPortfolioView from '../components/portfolio/pro/ProPortfolioView.jsx';
import ClearPortfolioView from '../components/portfolio/clear/ClearPortfolioView.jsx';
import { requestState } from '../lib/viewModel.js';
import {
  useCanonicalPortfolio,
  usePaperCashLedger,
  usePaperFills,
  usePaperPositions,
  usePaperReconciliationRuns,
  useRunPaperReconciliation,
} from '../hooks/usePaperApi.js';

// Own all portfolio queries and reconciliation mutation above the presentation boundary.
export default function PortfolioPage() {
  const { apiKey } = useApp();
  const portfolio = useCanonicalPortfolio();
  const positions = usePaperPositions();
  const fills = usePaperFills();
  const cash = usePaperCashLedger();
  const reconciliation = usePaperReconciliationRuns();
  const runReconciliation = useRunPaperReconciliation();

  const project = query => ({ ...requestState(query), refetch: () => query.refetch() });
  const props = { portfolio: project(portfolio), positions: project(positions), fills: project(fills), cash: project(cash),
    reconciliation: project(reconciliation), canReconcile: !!apiKey,
    runReconciliation: { ...requestState(runReconciliation), mutate: () => { if (apiKey && !runReconciliation.isPending) runReconciliation.mutate(); } } };
  return <ExperienceBoundary pro={<ProPortfolioView {...props} />} clear={<ClearPortfolioView {...props} />} />;
}
