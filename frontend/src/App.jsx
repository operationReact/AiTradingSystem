import { Route, Routes, Navigate } from 'react-router-dom';
import AppShell from './components/AppShell.jsx';
import OverviewPage from './pages/OverviewPage.jsx';
import SignalsPage from './pages/SignalsPage.jsx';
import PaperTradesPage from './pages/PaperTradesPage.jsx';
import RiskPage from './pages/RiskPage.jsx';
import InstrumentsPage from './pages/InstrumentsPage.jsx';
import MarketDataPage from './pages/MarketDataPage.jsx';
import BacktestingPage from './pages/BacktestingPage.jsx';

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<OverviewPage />} />
        <Route path="signals" element={<SignalsPage />} />
        <Route path="trades" element={<PaperTradesPage />} />
        <Route path="risk" element={<RiskPage />} />
        <Route path="instruments" element={<InstrumentsPage />} />
        <Route path="market-data" element={<MarketDataPage />} />
        <Route path="backtesting" element={<BacktestingPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
