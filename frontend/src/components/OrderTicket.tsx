import { useMemo, useState } from 'react';
import { api, ApiError } from '../lib/api';
import { refresher } from '../lib/account';
import { cents, outcomePrice, usd } from '../lib/format';
import { useAccount, useMarkets, useUi } from '../stores/stores';

const REASONS: Record<string, string> = {
  INSUFFICIENT_FUNDS: 'Not enough buying power',
  INSUFFICIENT_POSITION: "You don't hold enough of this outcome to sell",
  MARKET_NOT_OPEN: 'Market is halted or resolved',
  POST_ONLY_WOULD_CROSS: 'Post-only order would have traded immediately',
  FOK_NOT_FILLABLE: 'Not enough liquidity to fill the whole order',
  INVALID_PRICE: 'Price must be between 0.1¢ and 99.9¢',
  INVALID_QUANTITY: 'Invalid quantity',
};

/**
 * Order entry. Submissions are optimistic but honest: a "pending" row appears instantly, and nothing is shown
 * as filled until the server's durable result comes back. The clientOrderId makes retries safe.
 */
export function OrderTicket() {
  const ui = useUi();
  const id = ui.selected;
  const m = useMarkets((s) => (id == null ? undefined : s.byId[id]));
  const position = useAccount((s) => s.portfolio?.positions.find((p) => p.marketId === id));
  const buyingPower = useAccount((s) => s.portfolio?.available ?? 0);
  const [busy, setBusy] = useState(false);

  const o = ui.ticketOutcome;
  const a = ui.ticketAction;
  const isMarket = ui.ticketType === 'MARKET';
  const quantity = Math.floor(Number(ui.ticketQty) || 0);

  // What the market offers right now, in this outcome's terms.
  const best = useMemo(() => {
    if (!m) return { buy: -1, sell: -1 };
    const s = m.stats;
    // Buying YES lifts the YES ask; buying NO lifts the NO ask = 1000 - YES bid.
    return o === 'YES'
      ? { buy: s.bestAsk, sell: s.bestBid }
      : { buy: outcomePrice(s.bestBid, 'NO'), sell: outcomePrice(s.bestAsk, 'NO') };
  }, [m, o]);

  // An empty price field means "the best price on the other side", which is also what the placeholder shows.
  const typed = ui.ticketPrice.trim();
  const defaultPrice = a === 'BUY' ? best.buy : best.sell;
  const priceTicks = typed ? Math.round(Number(typed) * 10) : defaultPrice;
  const effPrice = isMarket ? (a === 'BUY' ? best.buy : best.sell) : priceTicks;
  const held = position ? (o === 'YES' ? position.yes - position.yesLocked : position.no - position.noLocked) : 0;
  const cost = effPrice > 0 ? effPrice * quantity : 0;
  const payout = quantity * 1000;

  let error: string | null = null;
  if (!m) error = 'Select a market';
  else if (m.stats.status !== 'OPEN') error = `Market is ${m.stats.status.toLowerCase()}`;
  else if (quantity <= 0) error = 'Enter a quantity';
  else if (!isMarket && (!(priceTicks >= 1 && priceTicks <= 999))) error = 'Price must be 0.1–99.9¢';
  else if (a === 'BUY' && !isMarket && cost > buyingPower) error = 'Exceeds buying power';
  else if (a === 'SELL' && quantity > held) error = `You hold ${held} ${o}`;
  else if (isMarket && effPrice < 0) error = 'No liquidity on that side';

  async function submit() {
    if (error || !m || busy) return;
    const clientOrderId = Date.now() * 1000 + Math.floor(Math.random() * 1000);
    const acct = useAccount.getState();
    acct.addPending({ key: clientOrderId, marketId: m.id, outcome: o, action: a, type: ui.ticketType, price: isMarket ? null : priceTicks, quantity, createdAt: Date.now() });
    setBusy(true);
    try {
      const r = await api.place({ marketId: m.id, outcome: o, side: a, type: ui.ticketType, price: isMarket ? undefined : priceTicks, quantity, clientOrderId });
      if (r.status === 'REJECTED') {
        ui.toast('err', REASONS[r.reason ?? ''] ?? `Rejected: ${r.reason}`);
      } else if (r.filledQuantity > 0) {
        const avg = r.avgPriceMilli / 1000;
        ui.toast('ok', `${a === 'BUY' ? 'Bought' : 'Sold'} ${r.filledQuantity} ${o} @ avg ${(avg / 10).toFixed(2)}¢${r.remainingQuantity > 0 && r.status === 'PARTIALLY_FILLED' ? ` · ${r.remainingQuantity} resting` : ''}`);
      } else if (r.status === 'OPEN') {
        ui.toast('info', `Order resting: ${a} ${quantity} ${o} @ ${cents(priceTicks)}`);
      } else {
        ui.toast('info', `Order ${r.status.toLowerCase().replace(/_/g, ' ')}${r.reason ? ` (${r.reason})` : ''}`);
      }
    } catch (e) {
      const msg = e instanceof ApiError && e.status === 503 ? 'Engine busy, try again' : (e as Error).message;
      ui.toast('err', msg);
    } finally {
      acct.resolvePending(clientOrderId);
      setBusy(false);
      refresher.now();
    }
  }

  return (
    <div className="ticket" onKeyDown={(e) => e.key === 'Enter' && submit()}>
      <div className="seg" role="radiogroup" aria-label="Outcome">
        <button className={`yes ${o === 'YES' ? 'on' : ''}`} onClick={() => ui.setTicket({ ticketOutcome: 'YES', ticketPrice: '' })}>
          YES {best.buy > 0 && a === 'BUY' ? cents(best.buy) : ''}
        </button>
        <button className={`no ${o === 'NO' ? 'on' : ''}`} onClick={() => ui.setTicket({ ticketOutcome: 'NO', ticketPrice: '' })}>
          NO {m && a === 'BUY' ? cents(outcomePrice(m.stats.bestBid, 'NO')) : ''}
        </button>
      </div>
      <div className="seg">
        <button className={a === 'BUY' ? 'on' : ''} onClick={() => ui.setTicket({ ticketAction: 'BUY' })}>Buy <kbd>B</kbd></button>
        <button className={a === 'SELL' ? 'on' : ''} onClick={() => ui.setTicket({ ticketAction: 'SELL' })}>Sell <kbd>S</kbd></button>
      </div>
      <div className="seg">
        <button className={!isMarket ? 'on' : ''} onClick={() => ui.setTicket({ ticketType: 'LIMIT' })}>Limit</button>
        <button className={isMarket ? 'on' : ''} onClick={() => ui.setTicket({ ticketType: 'MARKET' })}>Market</button>
      </div>
      <label className={`field ${isMarket ? 'disabled' : ''}`}>
        <label>Price</label>
        <input id="ticket-price" inputMode="decimal" disabled={isMarket} placeholder={best.buy > 0 ? (((a === 'BUY' ? best.buy : best.sell) / 10).toFixed(1)) : '50.0'}
               value={isMarket ? '' : ui.ticketPrice} onChange={(e) => ui.setTicket({ ticketPrice: e.target.value.replace(/[^0-9.]/g, '') })} />
        <span className="unit">¢</span>
      </label>
      <label className="field">
        <label>Shares</label>
        <input id="ticket-qty" inputMode="numeric" value={ui.ticketQty} onChange={(e) => ui.setTicket({ ticketQty: e.target.value.replace(/\D/g, '') })} />
      </label>
      <div className="quick">
        {[10, 100, 500, 1000].map((n) => <button key={n} onClick={() => ui.setTicket({ ticketQty: String(n) })}>{n}</button>)}
        {a === 'SELL' && held > 0 && <button onClick={() => ui.setTicket({ ticketQty: String(held) })}>Max</button>}
      </div>
      <div className="summary">
        {a === 'BUY' ? (
          <>
            <span>{isMarket ? 'Est. cost' : 'Max cost'}</span><b>{usd(cost)}</b>
            <span>Pays if {o}</span><b className="up">{usd(payout)}</b>
            <span>Potential profit</span><b>{cost > 0 ? `${usd(payout - cost)} (${(((payout - cost) / cost) * 100).toFixed(0)}%)` : '—'}</b>
          </>
        ) : (
          <>
            <span>{isMarket ? 'Est. proceeds' : 'Proceeds'}</span><b>{usd(cost)}</b>
            <span>You hold</span><b>{held} {o}</b>
          </>
        )}
      </div>
      <button className={`submit ${o === 'NO' ? 'no' : ''}`} disabled={!!error || busy} onClick={submit}>
        {busy ? 'Submitting…' : error ?? `${a === 'BUY' ? 'Buy' : 'Sell'} ${quantity} ${o}${isMarket ? ' at market' : ` @ ${cents(priceTicks)}`}`}
      </button>
      <div className="hint"><kbd>Y</kbd>/<kbd>N</kbd> outcome · <kbd>B</kbd>/<kbd>S</kbd> side · <kbd>P</kbd> price · <kbd>Enter</kbd> submit · <kbd>Esc</kbd> cancel all here</div>
    </div>
  );
}
