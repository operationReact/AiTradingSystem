import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useState } from 'react';

const AppContext = createContext(null);
import { EXPERIENCE_KEY, isExperience, normalizeExperience } from '../lib/experience.js';
function storedExperience() {
  try {
    const value = localStorage.getItem(EXPERIENCE_KEY);
    return normalizeExperience(value);
  } catch {
    return 'pro-terminal';
  }
}

/**
 * Global UI state. The operator API key lives in memory only (never localStorage),
 * so it is lost on reload by design. Theme and sidebar state are UI preferences.
 */
export function AppProvider({ children }) {
  const [apiKey, setApiKeyState] = useState('');
  const [experience, setExperienceState] = useState(storedExperience);
  const [theme, setTheme] = useState(() => localStorage.getItem('sf-theme') || 'dark');
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem('sf-sidebar') === 'collapsed');
  const [mobileOpen, setMobileOpen] = useState(false);

  // Layout effect so theme CSS variables are applied before child charts read them.
  useLayoutEffect(() => {
    document.documentElement.classList.toggle('light', theme === 'light');
    localStorage.setItem('sf-theme', theme);
  }, [theme]);

  useLayoutEffect(() => {
    document.documentElement.dataset.experience = experience;
    try { localStorage.setItem(EXPERIENCE_KEY, experience); } catch { /* storage unavailable */ }
  }, [experience]);

  useEffect(() => {
    localStorage.setItem('sf-sidebar', collapsed ? 'collapsed' : 'expanded');
  }, [collapsed]);

  const setApiKey = useCallback((key) => setApiKeyState(key.trim()), []);
  const setExperience = useCallback((next) => {
    if (isExperience(next)) setExperienceState(next);
  }, []);
  const toggleTheme = useCallback(() => setTheme((t) => (t === 'dark' ? 'light' : 'dark')), []);
  const toggleCollapsed = useCallback(() => setCollapsed((c) => !c), []);

  const value = useMemo(
    () => ({ apiKey, setApiKey, theme, toggleTheme, experience, setExperience, collapsed, toggleCollapsed, mobileOpen, setMobileOpen }),
    [apiKey, setApiKey, theme, toggleTheme, experience, setExperience, collapsed, toggleCollapsed, mobileOpen],
  );

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp() {
  const value = useContext(AppContext);
  if (!value) throw new Error('useApp must be used inside AppProvider');
  return value;
}
