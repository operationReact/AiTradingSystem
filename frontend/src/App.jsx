import { Route, Routes, Navigate } from 'react-router-dom';
import AppShell from './components/AppShell.jsx';
import OverviewPage from './pages/OverviewPage.jsx';
import SignalsPage from './pages/SignalsPage.jsx';
import PaperTradesPage from './pages/PaperTradesPage.jsx';
import PortfolioPage from './pages/PortfolioPage.jsx';
import RiskPage from './pages/RiskPage.jsx';
import ApprovalsPage from './pages/ApprovalsPage.jsx';
import SettingsPage from './pages/SettingsPage.jsx';
import InstrumentsPage from './pages/InstrumentsPage.jsx';
import MarketDataPage from './pages/MarketDataPage.jsx';
import BacktestingPage from './pages/BacktestingPage.jsx';
import ResearchPage from './pages/ResearchPage.jsx';
import CandidateDetailPage from './pages/CandidateDetailPage.jsx';
import OperationsPage from './pages/OperationsPage.jsx';
import NotificationsPage from './pages/NotificationsPage.jsx';
import AuditPage from './pages/AuditPage.jsx';

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<OverviewPage />} />
        <Route path="signals" element={<SignalsPage />} />
        <Route path="trades" element={<PaperTradesPage />} />
        <Route path="portfolio" element={<PortfolioPage />} />
        <Route path="risk" element={<RiskPage />} />
        <Route path="approvals" element={<ApprovalsPage />} />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="instruments" element={<InstrumentsPage />} />
        <Route path="market-data" element={<MarketDataPage />} />
        <Route path="backtesting" element={<BacktestingPage />} />
        <Route path="research" element={<ResearchPage />} />
        <Route path="research/candidates/:candidateId" element={<CandidateDetailPage />} />
        <Route path="operations" element={<OperationsPage />} />
        <Route path="notifications" element={<NotificationsPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
