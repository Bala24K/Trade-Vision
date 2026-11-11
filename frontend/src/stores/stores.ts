import { create } from 'zustand';
import type { MarketSummary, OrderResult, Portfolio, OrderView, Fill, TapeEntry, Outcome, Action } from '../lib/types';

/*
 * State is split by update frequency so a 20 Hz order-book update never re-renders the portfolio, and a
 * ticker for market 7 never re-renders the row for market 3. Components subscribe with narrow selectors.
 */

// ---------------------------------------------------------------- markets (tickers, ~4 Hz)

interface MarketsState {
  byId: Record<number, MarketSummary>;
  order: number[];
  setAll: (ms: MarketSummary[]) => void;
  merge: (ms: MarketSummary[]) => void;
}

export const useMarkets = create<MarketsState>((set) => ({
  byId: {},
  order: [],
  setAll: (ms) => set({ byId: Object.fromEntries(ms.map((m) => [m.id, m])), order: ms.map((m) => m.id) }),
  merge: (ms) =>
    set((s) => {
      const byId = { ...s.byId };
      let order = s.order;
      for (const m of ms) {
        if (!byId[m.id]) order = [...order, m.id];
        byId[m.id] = m;
      }
      return { byId, order };
    }),
}));

// ---------------------------------------------------------------- order book (selected market, ~20 Hz)

export type BookSide = Map<number, number>;

interface BookState {
  marketId: number | null;
  seq: number;
  bids: BookSide;
  asks: BookSide;
  /** Returns false when a sequence gap is detected; caller must resubscribe for a fresh snapshot. */
  apply: (m: { marketId: number; snapshot: boolean; seq: number; prevSeq: number; bids: number[][]; asks: number[][] }) => boolean;
  reset: (marketId: number | null) => void;
}

export const useBook = create<BookState>((set, get) => ({
  marketId: null,
  seq: 0,
  bids: new Map(),
  asks: new Map(),
  reset: (marketId) => set({ marketId, seq: 0, bids: new Map(), asks: new Map() }),
  apply: (m) => {
    const s = get();
    if (m.marketId !== s.marketId) return true;
    if (m.snapshot) {
      set({ seq: m.seq, bids: new Map(m.bids.map(([p, q]) => [p, q])), asks: new Map(m.asks.map(([p, q]) => [p, q])) });
      return true;
    }
    if (m.seq <= s.seq) return true; // older than our snapshot: already reflected
    if (m.prevSeq !== s.seq) return false; // missed a delta
    const bids = new Map(s.bids);
    const asks = new Map(s.asks);
    for (const [p, q] of m.bids) q === 0 ? bids.delete(p) : bids.set(p, q);
    for (const [p, q] of m.asks) q === 0 ? asks.delete(p) : asks.set(p, q);
    set({ seq: m.seq, bids, asks });
    return true;
  },
}));

// ---------------------------------------------------------------- trade tape (selected market)

interface TapeState {
  marketId: number | null;
  trades: TapeEntry[];
  reset: (marketId: number | null) => void;
  add: (marketId: number, trades: TapeEntry[], snapshot: boolean) => void;
  listeners: Set<(t: TapeEntry[]) => void>;
}

export const useTape = create<TapeState>((set, get) => ({
  marketId: null,
  trades: [],
  listeners: new Set(),
  reset: (marketId) => set({ marketId, trades: [] }),
  add: (marketId, trades, snapshot) => {
    if (marketId !== get().marketId) return;
    if (snapshot) {
      set({ trades: trades.slice(0, 100) });
      return;
    }
    // server sends oldest-first within a batch; tape displays newest-first
    const incoming = [...trades].reverse();
    set((s) => ({ trades: [...incoming, ...s.trades].slice(0, 100) }));
    get().listeners.forEach((l) => l(trades));
  },
}));

// ---------------------------------------------------------------- account

export interface PendingOrder {
  key: number; // clientOrderId
  marketId: number;
  outcome: Outcome;
  action: Action;
  type: 'LIMIT' | 'MARKET';
  price: number | null;
  quantity: number;
  createdAt: number;
}

interface AccountState {
  ready: boolean;
  portfolio: Portfolio | null;
  history: OrderView[];
  fills: Fill[];
  /** Optimistic: orders sent but not yet confirmed. Never shown as filled until the server says so. */
  pending: PendingOrder[];
  setPortfolio: (p: Portfolio) => void;
  setHistory: (h: OrderView[]) => void;
  setFills: (f: Fill[]) => void;
  addPending: (p: PendingOrder) => void;
  resolvePending: (key: number) => void;
  setReady: (r: boolean) => void;
}

export const useAccount = create<AccountState>((set) => ({
  ready: false,
  portfolio: null,
  history: [],
  fills: [],
  pending: [],
  setPortfolio: (portfolio) => set({ portfolio }),
  setHistory: (history) => set({ history }),
  setFills: (fills) => set({ fills }),
  addPending: (p) => set((s) => ({ pending: [p, ...s.pending] })),
  resolvePending: (key) => set((s) => ({ pending: s.pending.filter((p) => p.key !== key) })),
  setReady: (ready) => set({ ready }),
}));

// ---------------------------------------------------------------- connection

export type ConnStatus = 'connecting' | 'live' | 'reconnecting' | 'offline';

interface ConnState {
  status: ConnStatus;
  lastMessageAt: number;
  rttMs: number | null;
  set: (p: Partial<Omit<ConnState, 'set'>>) => void;
}

export const useConn = create<ConnState>((set) => ({
  status: 'connecting',
  lastMessageAt: 0,
  rttMs: null,
  set: (p) => set(p),
}));

// ---------------------------------------------------------------- UI

export interface Toast {
  id: number;
  kind: 'ok' | 'err' | 'info';
  text: string;
}

interface UiState {
  selected: number | null;
  ticketOutcome: Outcome;
  ticketAction: Action;
  ticketType: 'LIMIT' | 'MARKET';
  ticketPrice: string; // cents as typed, e.g. "63.5"
  ticketQty: string;
  toasts: Toast[];
  select: (id: number) => void;
  setTicket: (p: Partial<Pick<UiState, 'ticketOutcome' | 'ticketAction' | 'ticketType' | 'ticketPrice' | 'ticketQty'>>) => void;
  toast: (kind: Toast['kind'], text: string) => void;
  dismiss: (id: number) => void;
}

let toastId = 0;

export const useUi = create<UiState>((set) => ({
  selected: null,
  ticketOutcome: 'YES',
  ticketAction: 'BUY',
  ticketType: 'LIMIT',
  ticketPrice: '',
  ticketQty: '100',
  toasts: [],
  select: (selected) => set({ selected, ticketPrice: '' }),
  setTicket: (p) => set(p),
  toast: (kind, text) => {
    const id = ++toastId;
    set((s) => ({ toasts: [...s.toasts.slice(-3), { id, kind, text }] }));
    setTimeout(() => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), kind === 'err' ? 6000 : 3500);
  },
  dismiss: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
}));

export type { OrderResult };
