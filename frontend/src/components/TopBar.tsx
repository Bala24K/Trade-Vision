import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { usd, pnlClass } from '../lib/format';
import type { SystemStats } from '../lib/types';
import { useAccount, useConn, useMarkets, useUi, type View } from '../stores/stores';

function Nav() {
  const view = useUi((s) => s.view);
  const items: [View, string, string][] = [['trade', 'Trade', 'T'], ['analytics', 'Analytics', 'A'], ['leaderboard', 'Leaderboard', 'L']];
  return (
    <nav className="nav" aria-label="Views">
      {items.map(([v, label, key]) => (
        <button key={v} className={`navb ${view === v ? 'on' : ''}`} onClick={() => useUi.getState().setView(v)}
                aria-current={view === v ? 'page' : undefined} title={`${label} (Alt+${key})`}>
          {label}
        </button>
      ))}
    </nav>
  );
}

function useLivePnl() {
  // Positions come from the server; marks come from live tickers, so PnL moves with the market between refreshes.
  const portfolio = useAccount((s) => s.portfolio);
  const byId = useMarkets((s) => s.byId);
  if (!portfolio) return null;
  let value = 0;
  let unrealized = 0;
  for (const p of portfolio.positions) {
    const m = byId[p.marketId];
    const mark = m ? (m.stats.status === 'RESOLVED' ? 0 : m.stats.mark) : p.mark;
    const mv = p.yes * mark + p.no * (1000 - mark);
    value += mv;
    if (p.yes || p.no) unrealized += mv - p.cost;
  }
  return { equity: portfolio.cash + value, cash: portfolio.available, unrealized, realized: portfolio.realizedPnl };
}

function ConnectionPill() {
  const status = useConn((s) => s.status);
  const last = useConn((s) => s.lastMessageAt);
  const rtt = useConn((s) => s.rttMs);
  const [, tick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);
  const age = last ? Date.now() - last : 0;
  const stale = status === 'live' && age > 4000;
  const label = stale ? `Stale ${Math.round(age / 1000)}s` : status === 'live' ? `Live${rtt != null ? ` · ${rtt}ms` : ''}` : status === 'reconnecting' ? 'Reconnecting…' : status === 'connecting' ? 'Connecting…' : 'Offline';
  return (
    <span className={`pill ${stale ? 'reconnecting' : status}`} title="WebSocket status · round-trip time">
      <span className="dot" />
      {label}
    </span>
  );
}

function SystemPopover() {
  const [open, setOpen] = useState(false);
  const [s, setS] = useState<SystemStats | null>(null);
  useEffect(() => {
    let alive = true;
    const load = () => api.system().then((x) => alive && setS(x)).catch(() => {});
    load();
    const t = setInterval(load, 2000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, []);
  return (
    <div className="sys">
      <button className="pill" onClick={() => setOpen((o) => !o)} title="Engine & ingestion metrics">
        <span className="mono">{s ? `${s.commandsPerSecond.toLocaleString()} cmd/s` : '— cmd/s'}</span>
        <span className="dim">·</span>
        <span className="mono">{s ? `match p50 ${s.matchNanos.p50}ns` : ''}</span>
      </button>
      {open && s && (
        <div className="sys-pop">
          <h4>Engine</h4>
          <div className="kv">
            <span>Commands processed</span><b>{s.commandsProcessed.toLocaleString()}</b>
            <span>Durable sequence</span><b>{s.durableSequence.toLocaleString()}</b>
            <span>Match p50 / p99</span><b>{s.matchNanos.p50} / {s.matchNanos.p99} ns</b>
            <span>Ingress→applied p50 / p99</span><b>{s.ingressToAppliedMicros.p50} / {s.ingressToAppliedMicros.p99} µs</b>
            <span>Ingress→durable p50 / p99</span><b>{s.ingressToDurableMicros.p50} / {s.ingressToDurableMicros.p99} µs</b>
            <span>Queue depth in / out</span><b>{s.inboundQueueDepth} / {s.outboundQueueDepth}</b>
            <span>WebSocket clients</span><b>{s.websocketClients}</b>
          </div>
          <h4>Durability</h4>
          <div className="kv">
            <span>Snapshots</span><b>{s.snapshots?.mode === 'FOLLOWER' ? `follower @ ${s.snapshots.lastSnapshotSequence.toLocaleString()} (${s.snapshots.lastSnapshotMillis} ms)` : 'inline'}</b>
          </div>
          <h4>Projections</h4>
          <div className="kv">
            <span>Postgres</span>
            <b className={s.postgres ? (s.postgres.status.state === 'UP' ? 'up' : 'down') : 'dim'}>
              {s.postgres ? `${s.postgres.status.state.toLowerCase()} · lag ${s.postgres.lag}` : 'disabled'}
            </b>
            {s.postgres && <><span>Rows written / gaps</span><b>{s.postgres.status.rows.toLocaleString()} / {s.postgres.status.gaps}</b></>}
            <span>ClickHouse</span>
            <b className={s.clickhouse ? (s.clickhouse.up ? 'up' : 'down') : 'dim'}>
              {s.clickhouse ? (s.clickhouse.up ? `up · ${s.clickhouse.tradesWritten.toLocaleString()} trades` : 'down') : 'disabled'}
            </b>
          </div>
          <h4>Polymarket feed</h4>
          <div className="kv">
            <span>Status</span><b className={s.polymarket.connected ? 'up' : 'down'}>{s.polymarket.enabled ? (s.polymarket.connected ? 'connected' : 'disconnected') : 'disabled'}</b>
            <span>Tokens / messages</span><b>{s.polymarket.subscribedTokens} / {s.polymarket.messages.toLocaleString()}</b>
            <span>Applied / stale / dupes</span><b>{s.polymarket.applied.toLocaleString()} / {s.polymarket.stale} / {s.polymarket.duplicates}</b>
            <span>Resyncs / reconnects</span><b>{s.polymarket.crossedResyncs} / {s.polymarket.reconnects}</b>
            <span>Last message</span><b>{s.polymarket.lastMessageAgeMs < 0 ? '—' : `${(s.polymarket.lastMessageAgeMs / 1000).toFixed(1)}s ago`}</b>
          </div>
        </div>
      )}
    </div>
  );
}

export function TopBar() {
  const pnl = useLivePnl();
  return (
    <div className="top">
      <div className="brand">
        <span className="brand-mark" />
        TradeVision <small>paper terminal</small>
      </div>
      <Nav />
      <ConnectionPill />
      <SystemPopover />
      <span className="spacer" />
      {pnl ? (
        <>
          <div className="stat"><label>Equity</label><span>{usd(pnl.equity)}</span></div>
          <div className="stat"><label>Buying power</label><span>{usd(pnl.cash)}</span></div>
          <div className="stat"><label>Unrealized</label><span className={pnlClass(pnl.unrealized)}>{usd(pnl.unrealized, true)}</span></div>
          <div className="stat"><label>Realized</label><span className={pnlClass(pnl.realized)}>{usd(pnl.realized, true)}</span></div>
        </>
      ) : (
        <div className="skeleton" style={{ width: 320, height: 22 }} />
      )}
    </div>
  );
}
