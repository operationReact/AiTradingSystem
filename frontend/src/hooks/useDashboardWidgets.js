import { useState } from 'react';
import { useApp } from '../context/AppContext.jsx';
import { API } from '../lib/api.js';
import { requestState } from '../lib/viewModel.js';
import { useAnalyzeArticle, useNewsArticles, useNewsStatus, useAngelOneStatus, useTestAngelOneConnection } from './useApi.js';

const project = query => ({ ...requestState(query), refetch: () => query.refetch() });

export function useNewsIntelligenceController(enabled = true) {
  const { apiKey } = useApp();
  const status = useNewsStatus({ enabled });
  const articles = useNewsArticles({ page: 0, size: 5, enabled });
  const analyze = useAnalyzeArticle();
  return { connected: !!apiKey, status: project(status), articles: project(articles),
    analyze: { ...requestState(analyze), variables: analyze.variables },
    onAnalyze: id => { if (enabled && apiKey && status.data?.configured && !analyze.isPending) analyze.mutate(id); } };
}

export function useBrokerStatusController(enabled = true) {
  const { apiKey } = useApp();
  const status = useAngelOneStatus({ enabled });
  const test = useTestAngelOneConnection();
  return { connected: !!apiKey, status: project(status), test: requestState(test),
    onTest: () => { if (enabled && apiKey && !test.isPending) test.mutate(); } };
}

export function useRssNewsController(enabled = true) {
  const [token, setToken] = useState('');
  const [items, setItems] = useState([]);
  const [message, setMessage] = useState('');
  const [loading, setLoading] = useState(false);

  const load = async (event) => {
    event?.preventDefault();
    if (!enabled || !token.trim() || loading) return;
    setLoading(true);
    setMessage('');
    try {
      const response = await fetch(`${API}/news?limit=20&offset=0`, {
        headers: { Authorization: `Bearer ${token.trim()}`, Accept: 'application/json' },
      });
      if (response.status === 401) {
        throw new Error('The news-feed token was rejected. Check NEWS_API_TOKEN on the backend.');
      }
      if (!response.ok) throw new Error(`Unable to load news (HTTP ${response.status}).`);
      const page = await response.json();
      setItems(Array.isArray(page.items) ? page.items : []);
    } catch (error) {
      setMessage(error?.message || 'Unable to load news from the backend.');
      setItems([]);
    } finally {
      setLoading(false);
    }
  };

  return { token, setToken, items, message, loading, load };
}
