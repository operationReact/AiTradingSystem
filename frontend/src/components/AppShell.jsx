import { useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { useQueryClient, useIsFetching } from '@tanstack/react-query';
import { Activity, BarChart3, ChevronsLeft, ChevronsRight, ClipboardCheck, Gauge, KeyRound, ListOrdered, Menu, Moon, PanelsTopLeft, PieChart, Search, Settings2, ShieldAlert, Sun, X, LogOut } from 'lucide-react';
import { useApp } from '../context/AppContext.jsx';
import { cn } from '../lib/cn.js';
import { Button } from './ui.jsx';
import ModeControl from './ModeControl.jsx';

const NAV = [
  { to: '/', label: 'Overview', icon: Gauge, end: true },
  { to: '/signals', label: 'Signals', icon: Activity },
  { to: '/trades', label: 'Paper trades', icon: ListOrdered },
  { to: '/portfolio', label: 'Portfolio', icon: PieChart },
  { to: '/risk', label: 'Risk', icon: ShieldAlert },
  { to: '/approvals', label: 'Approvals', icon: ClipboardCheck },
  { to: '/instruments', label: 'Instruments', icon: Search },
  { to: '/market-data', label: 'Market data', icon: Activity },
  { to: '/research', label: 'Research', icon: Search },
  { to: '/backtesting', label: 'Backtesting', icon: BarChart3 },
  { to: '/operations', label: 'Operations', icon: Activity },
  { to: '/notifications', label: 'Notifications', icon: ClipboardCheck },
  { to: '/audit', label: 'Audit', icon: ListOrdered },
  { to: '/settings', label: 'Settings', icon: Settings2 },
];

const MOBILE_NAV = [NAV[0], NAV[1], NAV[2], NAV[3]];

const CRUMBS = { '': 'Overview', signals: 'Signals', trades: 'Paper trades', portfolio: 'Portfolio', risk: 'Risk', approvals: 'Approvals', instruments: 'Instruments', 'market-data': 'Market data', research: 'Research', backtesting: 'Backtesting', settings: 'Settings', operations: 'Operations', notifications: 'Notifications', audit: 'Audit' };

function Breadcrumbs() {
  const { pathname } = useLocation();
  const first = pathname.split('/').filter(Boolean)[0] ?? '';
  const label = CRUMBS[first] ?? 'Overview';
  return (
    <nav aria-label="Breadcrumb" className="text-xs text-muted">
      <span>SignalForge</span><span className="mx-1.5">/</span><span className="text-text">{label}</span>
    </nav>
  );
}

function Brand({ compact }) {
  return (
    <div className="flex items-center gap-2.5 overflow-hidden">
      <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-accent text-xs font-bold text-[rgb(var(--accent-contrast))]">SF</div>
      {!compact && <div className="min-w-0"><p className="truncate text-sm font-semibold text-text">SignalForge</p><p className="truncate text-[10px] uppercase tracking-widest text-muted">AI Trading Lab</p></div>}
    </div>
  );
}

function Nav({ compact, onNavigate }) {
  return (
    <nav aria-label="Primary" className="flex flex-col gap-0.5 px-2">
      {NAV.map(({ to, label, icon: Icon, end }) => (
        <NavLink key={to} to={to} end={end} onClick={onNavigate} title={compact ? label : undefined}
          className={({ isActive }) => cn('flex min-h-11 items-center gap-3 rounded-md px-2.5 text-sm transition-colors md:min-h-9', isActive ? 'bg-accent/15 text-text' : 'text-muted hover:bg-surface-2 hover:text-text', compact && 'justify-center px-0')}>
          <Icon className="h-4 w-4 shrink-0" aria-hidden />{!compact && <span className="truncate">{label}</span>}
        </NavLink>
      ))}
    </nav>
  );
}

function ApiKeyControl() {
  const { apiKey, setApiKey } = useApp();
  const qc = useQueryClient();
  const [draft, setDraft] = useState('');
  if (apiKey) {
    return (
      <div className="flex items-center gap-2">
        <span className="hidden items-center gap-1.5 text-xs text-gain sm:inline-flex"><span className="h-1.5 w-1.5 rounded-full bg-gain" aria-hidden />Operator key loaded</span>
        <Button size="sm" variant="ghost" icon={LogOut} aria-label="Clear operator API key" onClick={() => { setApiKey(''); qc.clear(); }}><span className="hidden sm:inline">Clear key</span></Button>
      </div>
    );
  }
  return (
    <form className="flex items-center gap-2" onSubmit={(e) => { e.preventDefault(); if (draft.trim()) { setApiKey(draft); setDraft(''); } }}>
      <label htmlFor="api-key" className="sr-only">Operator API key</label>
      <input id="api-key" type="password" autoComplete="off" spellCheck={false} value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="Operator API key" className="h-11 w-28 min-w-0 rounded-md border border-border bg-surface-2 px-2.5 text-xs text-text placeholder:text-muted focus:border-accent focus:outline-none sm:h-8 sm:w-56" />
      <Button size="sm" variant="primary" icon={KeyRound} type="submit" disabled={!draft.trim()}>Connect</Button>
    </form>
  );
}

export default function AppShell() {
  const { collapsed, toggleCollapsed, theme, toggleTheme, experience, setExperience, mobileOpen, setMobileOpen } = useApp();
  const fetching = useIsFetching();
  return (
    <div className="flex h-full">
      <aside className={cn('hidden shrink-0 flex-col border-r border-border bg-surface transition-[width] duration-200 md:flex', collapsed ? 'w-16' : 'w-56')}>
        <div className={cn('flex h-14 items-center border-b border-border', collapsed ? 'justify-center' : 'px-4')}><Brand compact={collapsed} /></div>
        <div className="flex-1 overflow-y-auto py-3"><Nav compact={collapsed} /></div>
        <div className="border-t border-border p-2"><button type="button" onClick={toggleCollapsed} aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} className="flex h-8 w-full items-center justify-center gap-2 rounded-md text-xs text-muted hover:bg-surface-2 hover:text-text">{collapsed ? <ChevronsRight className="h-4 w-4" aria-hidden /> : <><ChevronsLeft className="h-4 w-4" aria-hidden /><span>Collapse</span></>}</button></div>
      </aside>

      {mobileOpen && <div className="fixed inset-0 z-40 md:hidden"><button type="button" aria-label="Close navigation" className="absolute inset-0 bg-black/60" onClick={() => setMobileOpen(false)} /><aside className="relative flex h-full w-64 flex-col border-r border-border bg-surface"><div className="flex h-14 items-center justify-between border-b border-border px-4"><Brand /><button type="button" aria-label="Close navigation" onClick={() => setMobileOpen(false)} className="flex h-11 w-11 items-center justify-center rounded text-muted hover:text-text"><X className="h-4 w-4" aria-hidden /></button></div><div className="py-3"><Nav onNavigate={() => setMobileOpen(false)} /></div></aside></div>}

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-14 shrink-0 flex-wrap items-center gap-1 border-b border-border bg-surface/80 px-2 py-2 backdrop-blur sm:h-14 sm:flex-nowrap sm:gap-3 sm:px-4 sm:py-0">
          <button type="button" onClick={() => setMobileOpen(true)} aria-label="Open navigation" className="flex h-11 w-11 shrink-0 items-center justify-center rounded text-muted hover:bg-surface-2 hover:text-text md:hidden"><Menu className="h-4 w-4" aria-hidden /></button>
          <div className="hidden min-w-0 flex-1 sm:block"><Breadcrumbs /></div>
          {fetching > 0 && <span className="hidden text-xs text-muted lg:inline" aria-live="polite">Syncing…</span>}
          <ModeControl />
          <label className="flex shrink-0 items-center gap-1 text-xs text-muted" title="Appearance — Experience"><PanelsTopLeft className="hidden h-4 w-4 lg:block" aria-hidden /><span className="sr-only">Appearance — Experience</span><select aria-label="Appearance — Experience" value={experience} onChange={(e) => setExperience(e.target.value)} className="h-10 max-w-[105px] rounded-md border border-border bg-surface-2 px-1 text-[11px] text-text sm:max-w-none sm:px-2 sm:text-xs"><option value="pro-terminal">Pro Terminal</option><option value="clear-invest">Clear Invest</option></select></label>
          <ApiKeyControl />
          <button type="button" onClick={toggleTheme} aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'} className="flex h-11 w-11 shrink-0 items-center justify-center rounded-md text-muted hover:bg-surface-2 hover:text-text md:h-8 md:w-8">{theme === 'dark' ? <Sun className="h-4 w-4" aria-hidden /> : <Moon className="h-4 w-4" aria-hidden />}</button>
        </header>
        <main className="min-h-0 flex-1 overflow-y-auto pb-[calc(4.25rem+env(safe-area-inset-bottom))] md:pb-0"><div className="mx-auto w-full max-w-[1400px] px-3 py-3 sm:px-4 md:px-6 md:py-5"><Outlet /></div></main>
        <nav aria-label="Mobile primary" className="fixed inset-x-0 bottom-0 z-30 grid grid-cols-4 border-t border-border bg-surface pb-[env(safe-area-inset-bottom)] md:hidden">{MOBILE_NAV.map(({ to, label, icon: Icon, end }) => <NavLink key={to} to={to} end={end} className={({ isActive }) => cn('flex min-h-[56px] flex-col items-center justify-center gap-1 px-1 text-[11px] font-medium', isActive ? 'text-accent' : 'text-muted')}><Icon className="h-5 w-5" aria-hidden /><span className="truncate">{label}</span></NavLink>)}</nav>
      </div>
    </div>
  );
}
