import { memo, useMemo } from 'react';
import { c1, qty, timeOf } from '../lib/format';
import { useAccount, useBook, useTape, useUi } from '../stores/stores';

const LEVELS = 12;

/**
 * Ladder in YES terms (asks above, bids below). Clicking a level loads its price into the ticket, priced
 * for whichever outcome the ticket is set to (a YES ask at 64.0¢ is a NO bid at 36.0¢).
 * Dots mark levels where the user has resting orders.
 */
const Level = memo(function Level({ side, price, size, total, max, mine }: { side: 'bid' | 'ask'; price: number; size: number; total: number; max: number; mine: boolean }) {
  const onClick = () => {
    const ui = useUi.getState();
    const p = ui.ticketOutcome === 'YES' ? price : 1000 - price;
    ui.setTicket({ ticketPrice: (p / 10).toFixed(1), ticketType: 'LIMIT' });
  };
  return (
    <div className={`lvl ${side} ${mine ? 'mine' : ''}`} onClick={onClick} title="Click to use this price">
      <div className="bar" style={{ width: `${Math.max(2, (total / max) * 100)}%` }} />
      <span className={side === 'bid' ? 'up' : 'down'}>{c1(price)}</span>
      <span>{qty(size)}</span>
      <span className="dim">{qty(total)}</span>
    </div>
  );
});

export function OrderBook() {
  const bids = useBook((s) => s.bids);
  const asks = useBook((s) => s.asks);
  const marketId = useUi((s) => s.selected);
  const openOrders = useAccount((s) => s.portfolio?.openOrders);

  const view = useMemo(() => {
    const b = [...bids.entries()].sort((x, y) => y[0] - x[0]).slice(0, LEVELS);
    const a = [...asks.entries()].sort((x, y) => x[0] - y[0]).slice(0, LEVELS);
    let cb = 0;
    const bRows = b.map(([p, q]) => ({ p, q, t: (cb += q) }));
    let ca = 0;
    const aRows = a.map(([p, q]) => ({ p, q, t: (ca += q) }));
    const max = Math.max(cb, ca, 1);
    return { bRows, aRows: aRows.reverse(), max, bestBid: b[0]?.[0] ?? -1, bestAsk: a[0]?.[0] ?? -1 };
  }, [bids, asks]);

  const minePrices = useMemo(() => {
    const set = new Set<string>();
    for (const o of openOrders ?? []) {
      if (o.marketId !== marketId) continue;
      const bookPrice = o.outcome === 'YES' ? o.limitPrice : 1000 - o.limitPrice;
      const isBid = (o.outcome === 'YES') === (o.action === 'BUY');
      set.add(`${isBid ? 'b' : 'a'}${bookPrice}`);
    }
    return set;
  }, [openOrders, marketId]);

  const spread = view.bestBid >= 0 && view.bestAsk >= 0 ? view.bestAsk - view.bestBid : -1;
  const mid = spread >= 0 ? (view.bestBid + view.bestAsk) / 2 : -1;

  return (
    <>
      <div className="panel-head">Order book<span className="spacer" /><span className="dim" style={{ textTransform: 'none', letterSpacing: 0 }}>YES ¢</span></div>
      <div className="book">
        <div className="book-cols"><span>Price</span><span>Size</span><span>Total</span></div>
        <div className="book-side asks">
          {view.aRows.map((r) => <Level key={r.p} side="ask" price={r.p} size={r.q} total={r.t} max={view.max} mine={minePrices.has(`a${r.p}`)} />)}
        </div>
        <div className="spread">
          <span className="mono">{mid >= 0 ? `${(mid / 10).toFixed(2)}¢ mid` : 'no two-sided market'}</span>
          <span className="mono">spread {spread >= 0 ? `${(spread / 10).toFixed(1)}¢` : '—'}</span>
        </div>
        <div className="book-side">
          {view.bRows.map((r) => <Level key={r.p} side="bid" price={r.p} size={r.q} total={r.t} max={view.max} mine={minePrices.has(`b${r.p}`)} />)}
        </div>
      </div>
      <TradeTape />
    </>
  );
}

function TradeTape() {
  const trades = useTape((s) => s.trades);
  return (
    <div className="tape">
      {trades.length === 0 && <div className="empty">Waiting for trades…</div>}
      {trades.map((t) => (
        <div className="t" key={t.tradeId}>
          <span className={t.aggressor === 'BID' ? 'up' : 'down'}>{c1(t.price)}</span>
          <span>{qty(t.quantity)}</span>
          <span className="dim">{timeOf(t.ts)}</span>
        </div>
      ))}
    </div>
  );
}
