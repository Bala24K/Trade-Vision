import { useBook, useConn, useMarkets, useTape } from '../stores/stores';
import { getToken } from './api';

/**
 * One multiplexed WebSocket for the whole terminal.
 *
 * - Reconnects with exponential backoff + jitter (0.5 s .. 10 s) and re-establishes auth and subscriptions.
 * - Liveness: an app-level ping every 5 s; if nothing at all arrives for 8 s the socket is treated as dead
 *   (TCP can stay "open" through a dead path for minutes) and torn down.
 * - Book integrity: deltas are sequence-checked in the store; on a gap we resubscribe for a fresh snapshot.
 */
class Feed {
  private ws: WebSocket | null = null;
  private attempt = 0;
  private market: number | null = null;
  private pingTimer: number | undefined;
  private lastPingSent = 0;
  private onAccount: (() => void) | null = null;

  start(onAccount: () => void) {
    this.onAccount = onAccount;
    this.connect();
    this.pingTimer = window.setInterval(() => this.heartbeat(), 5000);
  }

  private connect() {
    useConn.getState().set({ status: this.attempt === 0 ? 'connecting' : 'reconnecting' });
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${location.host}/ws`);
    this.ws = ws;
    ws.onopen = () => {
      this.attempt = 0;
      useConn.getState().set({ status: 'live', lastMessageAt: Date.now() });
      const token = getToken();
      if (token) this.send({ op: 'auth', token });
      if (this.market != null) this.send({ op: 'subscribe', marketId: this.market });
    };
    ws.onmessage = (ev) => this.handle(JSON.parse(ev.data));
    ws.onclose = () => this.scheduleReconnect(ws);
    ws.onerror = () => ws.close();
  }

  private scheduleReconnect(ws: WebSocket) {
    if (this.ws !== ws) return;
    this.ws = null;
    useConn.getState().set({ status: 'reconnecting' });
    const base = Math.min(10_000, 500 * 2 ** this.attempt++);
    setTimeout(() => this.connect(), base * (0.5 + Math.random()));
  }

  private heartbeat() {
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (Date.now() - useConn.getState().lastMessageAt > 8000) {
      ws.close(); // stale: force reconnect
      return;
    }
    this.lastPingSent = performance.now();
    this.send({ op: 'ping' });
  }

  private send(msg: unknown) {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  }

  /** Switch the single detailed subscription (book + trades) to another market. */
  focus(marketId: number) {
    if (this.market != null && this.market !== marketId) this.send({ op: 'unsubscribe', marketId: this.market });
    this.market = marketId;
    useBook.getState().reset(marketId);
    useTape.getState().reset(marketId);
    this.send({ op: 'subscribe', marketId });
  }

  authenticate() {
    const token = getToken();
    if (token) this.send({ op: 'auth', token });
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private handle(m: any) {
    useConn.getState().set({ lastMessageAt: Date.now() });
    switch (m.type) {
      case 'tickers':
        useMarkets.getState().merge(m.items);
        break;
      case 'book':
        if (!useBook.getState().apply(m)) {
          // gap: ask for a fresh snapshot
          this.send({ op: 'subscribe', marketId: m.marketId });
        }
        break;
      case 'trades':
        useTape.getState().add(m.marketId, m.trades, !!m.snapshot);
        break;
      case 'account':
        this.onAccount?.();
        break;
      case 'pong':
        useConn.getState().set({ rttMs: Math.round(performance.now() - this.lastPingSent) });
        break;
    }
  }

  stop() {
    window.clearInterval(this.pingTimer);
    this.ws?.close();
  }
}

export const feed = new Feed();
