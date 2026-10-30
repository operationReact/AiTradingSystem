import { Settings2 } from 'lucide-react';
import ApprovalPolicySettings from '../components/ApprovalPolicySettings.jsx';

export default function SettingsPage() {
  return (
    <div className="space-y-4">
      <div>
        <div className="flex items-center gap-2">
          <Settings2 className="h-5 w-5 text-accent" aria-hidden />
          <h1 className="text-xl font-semibold text-text">Settings</h1>
        </div>
        <p className="mt-1 text-sm text-muted">Execution authorization controls and operator-facing policy configuration.</p>
      </div>
      <ApprovalPolicySettings />
    </div>
  );
}
