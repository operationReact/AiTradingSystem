import InstrumentMasterPanel from '../components/instruments/InstrumentMasterPanel.jsx';

export default function InstrumentsPage() {
  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-lg font-semibold text-text">Instrument catalogue</h1>
        <p className="mt-1 max-w-3xl text-xs leading-5 text-muted">
          Verified symbol-to-token reference data for the AI trading system. Search uses the active catalogue version.
          Instrument-master metadata is not a real-time quote and does not itself indicate trade eligibility.
        </p>
      </div>
      <InstrumentMasterPanel />
    </div>
  );
}
