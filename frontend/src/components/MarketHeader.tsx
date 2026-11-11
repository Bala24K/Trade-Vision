import { cents, pnlClass, qty, usdCompact } from '../lib/format';
import { useMarkets, useUi } from '../stores/stores';

export function MarketHeader() {
  const id = useUi((s) => s.selected);
  const m = useMarkets((s) => (id == null ? undefined : s.byId[id]));
  if (!m) return <div className="mhead"><div className="skeleton" style={{ height: 34, flex: 1 }} /></div>;
  const s = m.stats;
  const spread = s.bestBid >= 0 && s.bestAsk >= 0 ? s.bestAsk - s.bestBid : -1;
  return (
    <div className="mhead">
      <h1>
        <span className={`badge ${m.source === 'POLYMARKET' ? 'poly' : 'sim'}`} style={{ marginRight: 8 }}>
          {m.source === 'POLYMARKET' ? 'POLYMARKET MIRROR' : 'SIMULATED'}
        </span>
        {m.question}
      </h1>
      <div className="stat">
        <label>YES · chance</label>
        <span className="big">{cents(s.mark)}</span>
      </div>
      <div className="stat"><label>24h</label><span className={pnlClass(s.change24h)}>{s.change24h > 0 ? '+' : ''}{(s.change24h / 10).toFixed(1)}¢</span></div>
      <div className="stat"><label>Bid / Ask</label><span>{cents(s.bestBid)} / {cents(s.bestAsk)}</span></div>
      <div className="stat"><label>Spread</label><span>{spread < 0 ? '—' : cents(spread)}</span></div>
      <div className="stat"><label>Volume</label><span>{usdCompact(s.notional)}</span></div>
      <div className="stat"><label>Trades</label><span>{qty(s.trades)}</span></div>
      {m.reference && (
        <div className="stat" title="Live best bid/ask on Polymarket; our market makers anchor to its mid">
          <label>Polymarket</label>
          <span style={{ color: '#8fb0ff' }}>{cents(m.reference.bestBid)} / {cents(m.reference.bestAsk)}</span>
        </div>
      )}
      {s.status !== 'OPEN' && (
        <div className="stat"><label>Status</label><span className="down">{s.status}{s.winner ? ` → ${s.winner}` : ''}</span></div>
      )}
    </div>
  );
}
