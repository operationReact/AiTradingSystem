export const API = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api';

export class ApiError extends Error {
  constructor(message, status, body) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

/** Sends the operator key only as a header. The key is never stored or logged here. */
export async function apiFetch(path, { apiKey, method = 'GET', body, headers = {}, signal } = {}) {
  let res;
  try {
    res = await fetch(`${API}${path}`, {
      method,
      signal,
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(apiKey ? { 'X-API-Key': apiKey } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch (err) {
    if (err?.name === 'AbortError') throw err;
    throw new ApiError('Backend unreachable', 0, null);
  }
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (!res.ok) {
    const message =
      res.status === 401 ? 'Operator key rejected' : json?.detail || json?.title || `Request failed (${res.status})`;
    throw new ApiError(message, res.status, json);
  }
  return json;
}

/** Downloads a protected non-JSON API response without persisting or exposing the operator key. */
export async function apiDownload(path, { apiKey, signal } = {}) {
  let res;
  try {
    res = await fetch(`${API}${path}`, {
      signal,
      headers: {
        Accept: 'text/csv,*/*',
        ...(apiKey ? { 'X-API-Key': apiKey } : {}),
      },
    });
  } catch (err) {
    if (err?.name === 'AbortError') throw err;
    throw new ApiError('Backend unreachable', 0, null);
  }
  if (!res.ok) {
    let body = null;
    try { body = await res.json(); } catch { /* non-JSON error */ }
    const message = res.status === 401 ? 'Operator key rejected' : body?.detail || body?.title || `Request failed (${res.status})`;
    throw new ApiError(message, res.status, body);
  }
  return {
    blob: await res.blob(),
    contentDisposition: res.headers.get('Content-Disposition') || '',
  };
}
