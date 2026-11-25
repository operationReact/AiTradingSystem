import { useInstrumentMasterController } from '../hooks/useSetupControllers.js';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import ProInstrumentsView from '../components/instruments/pro/ProInstrumentsView.jsx';
import ClearInstrumentsView from '../components/instruments/clear/ClearInstrumentsView.jsx';

export default function InstrumentsPage() {
  const instrumentController = useInstrumentMasterController();
  return <ExperienceBoundary pro={<ProInstrumentsView instrumentController={instrumentController} />} clear={<ClearInstrumentsView instrumentController={instrumentController} />} />;
}
