import { useState } from 'react';
import { useApp } from '../context/AppContext.jsx';
import { apiDownload } from '../lib/api.js';
import { useOperatorEvidence } from '../hooks/useOperatorEvidence.js';
import ExperienceBoundary from '../components/experience/ExperienceBoundary.jsx';
import AuditView from '../components/audit/AuditView.jsx';

export default function AuditPage() {
  const { apiKey } = useApp();
  const [draft, setDraft] = useState({ eventType: '', entityId: '', actor: '' });
  const [filters, setFilters] = useState(draft);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState(null);
  const params = new URLSearchParams(Object.entries(filters).filter(([,value]) => value.trim()).map(([key,value]) => [key,value.trim()]));
  const query = useOperatorEvidence(['audit', filters, page], `/audit?${params}&page=${page}&size=20`);
  async function exportCsv() {
    if (!apiKey || exporting) return;
    setExporting(true); setExportError(null);
    try {
      const { blob } = await apiDownload(`/audit/export?${params}`, { apiKey });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a'); link.href = url; link.download = 'audit.csv'; link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) { setExportError(error); } finally { setExporting(false); }
  }
  const props = { query, draft, onDraft: setDraft, onApply: e => { e.preventDefault(); setFilters({ ...draft }); setPage(0); },
    page, onPage: setPage, onExport: exportCsv, exporting, exportError };
  return <ExperienceBoundary pro={<AuditView {...props} />} clear={<AuditView {...props} clear />} />;
}
