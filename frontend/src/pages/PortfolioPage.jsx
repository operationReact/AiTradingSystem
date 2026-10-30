import { useApp } from '../context/AppContext.jsx';
import PortfolioWorkspace from '../components/portfolio/shared/PortfolioWorkspace.jsx';
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

  return <PortfolioWorkspace {...{ portfolio, positions, fills, cash, reconciliation, runReconciliation }} canReconcile={!!apiKey} />;
}
