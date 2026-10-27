import MarketDataPanel from '../components/marketdata/MarketDataPanel.jsx';

export default function MarketDataPage() {
  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold text-text">Historical market data</h1>
        <p className="mt-1 max-w-3xl text-xs leading-5 text-muted">
          Fetch verified-instrument OHLCV history from Angel One SmartAPI, persist it in PostgreSQL, and resume long requests from a saved checkpoint. This screen only retrieves market data; it does not submit broker orders.
        </p>
      </div>
      <MarketDataPanel />
    </div>
  );
}
