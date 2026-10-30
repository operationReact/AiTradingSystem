import { useHistoricalDataController, useMarketOperationsController } from '../hooks/useSetupControllers.js';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import ProMarketDataView from '../components/marketdata/pro/ProMarketDataView.jsx';
import ClearMarketDataView from '../components/marketdata/clear/ClearMarketDataView.jsx';

export default function MarketDataPage() {
  const historyController = useHistoricalDataController();
  const operationsController = useMarketOperationsController();
  return <ExperienceBoundary pro={<ProMarketDataView historyController={historyController} operationsController={operationsController} />} clear={<ClearMarketDataView historyController={historyController} operationsController={operationsController} />} />;
}
