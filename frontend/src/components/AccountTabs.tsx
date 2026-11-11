import { useState } from 'react';
import { api } from '../lib/api';
import { refresher } from '../lib/account';
import { c1, cents, pnlClass, qty, timeOf, usd } from '../lib/format';
import { useAccount, useMarkets, useUi } from '../stores/stores';

type Tab = 'positions' | 'open' | 'history' | 'fills';

function Q({ id }: { id: number }) {
  const q = useMarkets((s) => s.byId[id]?.question ?? `#${id}`);
  return <span className="q" title={q} onClick={() => useUi.getState().select(id)}>{q}</span>;
}

function Positions() {
  const positions = useAccount((s) => s.portfolio?.positions ?? []);
  const byId = useMarkets((s) => s.byId);
  const rows = positions.filter((p) => p.yes || p.no);
  if (!rows.length) return <div className="empty">No open positions. Buy YES or NO from the ticket →</div>;
  const cols = { gridTemplateColumns: 'minmax(160px,1fr) 64px 80px 80px 90px 100px 100px' };
  return (
    <>
      <div className="tr th" style={cols}><span>Market</span><span>Side</span><span className="r">Shares</span><span className="r">Avg</span><span className="r">Mark</span><span className="r">Value</span><span className="r">P&L</span></div>
      {rows.flatMap((p) => {
        const m = byId[p.marketId];
        const mark = m ? m.stats.mark : p.mark;
        const out = [];
        if (p.yes) out.push({ side: 'YES' as const, n: p.yes, avg: p.avgYesMilli / 1000, px: mark, cost: p.yes * p.avgYesMilli / 1000 });
        if (p.no) out.push({ side: 'NO' as const, n: p.no, avg: p.avgNoMilli / 1000, px: 1000 - mark, cost: p.no * p.avgNoMilli / 1000 });
        return out.map((r) => {
          const value = r.n * r.px;
          const pnl = value - r.cost;
          return (
            <div className="tr" style={cols} key={`${p.marketId}-${r.side}`}>
              <Q id={p.marketId} />
              <span><span className={`tag ${r.side.toLowerCase()}`}>{r.side}</span></span>
              <span className="r">{qty(r.n)}</span>
              <span className="r">{(r.avg / 10).toFixed(2)}¢</span>
              <span className="r">{cents(r.px)}</span>
              <span className="r">{usd(value)}</span>
              <span className={`r ${pnlClass(pnl)}`}>{usd(pnl, true)} <span className="dim">{r.cost > 0 ? `${((pnl / r.cost) * 100).toFixed(1)}%` : ''}</span></span>
            </div>
          );
        });
      })}
    </>
  );
}

function OpenOrders() {
  const orders = useAccount((s) => s.portfolio?.openOrders ?? []);
  const pending = useAccount((s) => s.pending);
  const toast = useUi((s) => s.toast);
  const cols = { gridTemplateColumns: 'minmax(160px,1fr) 90px 70px 90px 110px 80px 60px' };
  if (!orders.length && !pending.length) return <div className="empty">No open orders</div>;
  const cancel = async (id: number) => {
    try {
      await api.cancel(id);
      refresher.now();
    } catch (e) {
      toast('err', `Cancel failed: ${(e as Error).message}`);
    }
  };
  return (
    <>
      <div className="tr th" style={cols}><span>Market</span><span>Order</span><span className="r">Price</span><span className="r">Filled</span><span className="r">Status</span><span className="r">Time</span><span /></div>
      {pending.map((p) => (
        <div className="tr pending" style={cols} key={`p${p.key}`}>
          <Q id={p.marketId} />
          <span>{p.action} <span className={`tag ${p.outcome.toLowerCase()}`}>{p.outcome}</span></span>
          <span className="r">{p.price == null ? 'MKT' : c1(p.price)}</span>
          <span className="r">0/{qty(p.quantity)}</span>
          <span className="r">sending…</span>
          <span className="r">—</span>
          <span />
        </div>
      ))}
      {orders.map((o) => (
        <div className="tr" style={cols} key={o.orderId}>
          <Q id={o.marketId} />
          <span>{o.action} <span className={`tag ${o.outcome.toLowerCase()}`}>{o.outcome}</span></span>
          <span className="r">{c1(o.limitPrice)}</span>
          <span className="r">{qty(o.filled)}/{qty(o.quantity)}</span>
          <span className="r">{o.status === 'PARTIALLY_FILLED' ? 'partial' : 'open'}</span>
          <span className="r dim">{timeOf(o.createdTs)}</span>
          <span className="r"><button className="x" onClick={() => cancel(o.orderId)} aria-label="Cancel order">✕</button></span>
        </div>
      ))}
    </>
  );
}

function History() {
  const history = useAccount((s) => s.history);
  const cols = { gridTemplateColumns: 'minmax(160px,1fr) 90px 70px 90px 90px 130px 80px' };
  if (!history.length) return <div className="empty">No order history yet</div>;
  return (
    <>
      <div className="tr th" style={cols}><span>Market</span><span>Order</span><span className="r">Limit</span><span className="r">Filled</span><span className="r">Avg</span><span className="r">Status</span><span className="r">Time</span></div>
      {history.map((o) => (
        <div className="tr" style={cols} key={o.orderId}>
          <Q id={o.marketId} />
          <span>{o.action} <span className={`tag ${o.outcome.toLowerCase()}`}>{o.outcome}</span></span>
          <span className="r">{c1(o.limitPrice)}</span>
          <span className="r">{qty(o.filled)}/{qty(o.quantity)}</span>
          <span className="r">{o.filled ? (o.filledValue / o.filled / 10).toFixed(2) : '—'}</span>
          <span className={`r ${o.status === 'FILLED' ? 'up' : 'muted'}`} title={o.reason ?? ''}>{o.status.toLowerCase()}{o.reason && o.status !== 'FILLED' ? ` · ${o.reason.toLowerCase().replace(/_/g, ' ')}` : ''}</span>
          <span className="r dim">{timeOf(o.updatedTs)}</span>
        </div>
      ))}
    </>
  );
}

function Fills() {
  const fills = useAccount((s) => s.fills);
  const cols = { gridTemplateColumns: 'minmax(160px,1fr) 90px 80px 80px 70px 80px' };
  if (!fills.length) return <div className="empty">No fills yet</div>;
  return (
    <>
      <div className="tr th" style={cols}><span>Market</span><span>Trade</span><span className="r">Price</span><span className="r">Shares</span><span className="r">Role</span><span className="r">Time</span></div>
      {fills.map((f) => (
        <div className="tr" style={cols} key={`${f.tradeId}-${f.orderId}`}>
          <Q id={f.marketId} />
          <span>{f.action} <span className={`tag ${f.outcome.toLowerCase()}`}>{f.outcome}</span></span>
          <span className="r">{c1(f.price)}</span>
          <span className="r">{qty(f.quantity)}</span>
          <span className="r dim">{f.maker ? 'maker' : 'taker'}</span>
          <span className="r dim">{timeOf(f.ts)}</span>
        </div>
      ))}
    </>
  );
}

export function AccountTabs() {
  const [tab, setTab] = useState<Tab>('positions');
  const nPos = useAccount((s) => (s.portfolio?.positions ?? []).filter((p) => p.yes || p.no).length);
  const nOpen = useAccount((s) => (s.portfolio?.openOrders.length ?? 0) + s.pending.length);
  const tabs: [Tab, string, number?][] = [['positions', 'Positions', nPos], ['open', 'Open orders', nOpen], ['history', 'History'], ['fills', 'Fills']];
  return (
    <>
      <div className="panel-head">
        <div className="tabs" role="tablist">
          {tabs.map(([k, label, n]) => (
            <button key={k} role="tab" aria-selected={tab === k} className={`tab ${tab === k ? 'on' : ''}`} onClick={() => setTab(k)}>
              {label}{n ? <span className="n">{n}</span> : null}
            </button>
          ))}
        </div>
        <span className="spacer" />
        {tab === 'open' && nOpen > 0 && <button className="x" onClick={() => api.cancelAll().then(refresher.now)}>Cancel all</button>}
      </div>
      <div className="table">
        {tab === 'positions' && <Positions />}
        {tab === 'open' && <OpenOrders />}
        {tab === 'history' && <History />}
        {tab === 'fills' && <Fills />}
      </div>
    </>
  );
}
