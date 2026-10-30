import MarketDataPanel from '../../marketdata/MarketDataPanel.jsx';
import MarketDataOperationsPanel from '../../marketdata/MarketDataOperationsPanel.jsx';

export default function MarketDataWorkspace({ historyController, operationsController, clear = false }) {
  return (
    <div className={clear ? "flex flex-col gap-6" : "flex flex-col gap-5"}>
      <div>
        <h1 className="text-lg font-semibold text-text">Historical market data</h1>
        <p className="mt-1 max-w-3xl text-xs leading-5 text-muted">
          Fetch verified-instrument OHLCV history from Angel One SmartAPI, persist it in PostgreSQL, inspect data quality and exchange sessions, and reconcile detected gaps. This workspace never submits broker orders.
        </p>
      </div>
      <MarketDataPanel controller={historyController} />
      <MarketDataOperationsPanel controller={operationsController} />
    </div>
  );
}
