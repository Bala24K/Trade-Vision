import type { Candle, Fill, MarketSummary, OrderResult, OrderView, Portfolio, SystemStats, TapeEntry } from './types';

const TOKEN_KEY = 'tv.token';

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function setToken(t: string) {
  try {
    localStorage.setItem(TOKEN_KEY, t);
  } catch {
    /* private mode: session-only */
  }
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function req<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const res = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  });
  if (!res.ok) {
    let msg = res.statusText;
    try {
      const body = await res.json();
      msg = body.error ?? body.message ?? msg;
    } catch {
      /* non-JSON */
    }
    throw new ApiError(res.status, msg);
  }
  return res.json() as Promise<T>;
}

export const api = {
  async ensureSession(): Promise<string> {
    const existing = getToken();
    if (existing) {
      try {
        await req<Portfolio>('/api/me');
        return existing;
      } catch (e) {
        if (!(e instanceof ApiError) || (e.status !== 401 && e.status !== 404)) throw e;
      }
    }
    const s = await req<{ token: string; accountId: number }>('/api/session', { method: 'POST' });
    setToken(s.token);
    return s.token;
  },
  resetSession: () => {
    try {
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* ignore */
    }
  },
  markets: () => req<MarketSummary[]>('/api/markets'),
  market: (id: number) =>
    req<{ market: MarketSummary; depth: unknown; trades: TapeEntry[] }>(`/api/markets/${id}`),
  candles: (id: number, tf: string) => req<Candle[]>(`/api/markets/${id}/candles?tf=${tf}`),
  me: () => req<Portfolio>('/api/me'),
  history: () => req<OrderView[]>('/api/me/orders?status=history'),
  fills: () => req<Fill[]>('/api/me/fills'),
  system: () => req<SystemStats>('/api/system'),
  place: (body: {
    marketId: number;
    outcome: string;
    side: string;
    type: string;
    price?: number;
    quantity: number;
    tif?: string;
    clientOrderId: number;
  }) => req<OrderResult>('/api/orders', { method: 'POST', body: JSON.stringify(body) }),
  cancel: (orderId: number) => req<unknown>(`/api/orders/${orderId}`, { method: 'DELETE' }),
  cancelAll: (marketId?: number) =>
    req<unknown>(`/api/orders${marketId ? `?marketId=${marketId}` : ''}`, { method: 'DELETE' }),
};
