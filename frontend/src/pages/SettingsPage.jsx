import { useApprovalSettingsController } from '../hooks/useSetupControllers.js';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import ProSettingsView from '../components/settings/pro/ProSettingsView.jsx';
import ClearSettingsView from '../components/settings/clear/ClearSettingsView.jsx';

export default function SettingsPage() {
  const settingsController = useApprovalSettingsController();
  return <ExperienceBoundary pro={<ProSettingsView settingsController={settingsController} />} clear={<ClearSettingsView settingsController={settingsController} />} />;
}
