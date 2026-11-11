import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { pnlClass, usd } from '../lib/format';
import type { Leaderboard } from '../lib/types';
import { useAccount } from '../stores/stores';

export function LeaderboardView() {
  const [bots, setBots] = useState(false);
  const [lb, setLb] = useState<Leaderboard | null>(null);
  const me = useAccount((s) => s.portfolio?.accountId);

  useEffect(() => {
    let alive = true;
    const load = () => api.leaderboard(bots).then((x) => alive && setLb(x)).catch(() => {});
    load();
    const t = window.setInterval(load, 5000);
    return () => {
      alive = false;
      window.clearInterval(t);
    };
  }, [bots]);

  const cols = { gridTemplateColumns: '56px minmax(140px,1fr) 130px 130px 130px 90px' };
  const max = Math.max(1, ...(lb?.rows ?? []).map((r) => Math.abs(r.totalPnl)));
  return (
    <div className="view lview">
      <div className="panel">
        <div className="panel-head">
          Leaderboard · total P&amp;L (realized + mark-to-market)
          <span className="spacer" />
          <label className="toggle">
            <input type="checkbox" checked={bots} onChange={(e) => setBots(e.target.checked)} /> include bots
          </label>
          {lb && <span className={`pill src ${lb.source === 'postgres' ? 'live' : ''}`}
                       title={lb.source === 'postgres' ? 'One SQL query over the positions and markets projection tables' : 'Computed from the in-memory read model'}>
            <span className="dot" />{lb.source}</span>}
        </div>
        <div className="table">
          <div className="tr th" style={cols}>
            <span>#</span><span>Account</span><span className="r">Total P&amp;L</span><span className="r">Realized</span>
            <span className="r">Unrealized</span><span className="r">Open pos.</span>
          </div>
          {!lb && <div className="empty">Loading…</div>}
          {lb && !lb.rows.length && <div className="empty">No traders with positions yet. Place a trade and come back.</div>}
          {lb?.rows.map((r) => (
            <div className={`tr ${r.accountId === me ? 'me' : ''}`} style={cols} key={r.accountId}>
              <span className="mono">{r.rank}</span>
              <span>
                {r.accountId === me ? <b>You</b> : r.bot ? <span className="dim">bot #{r.accountId}</span> : `Trader #${r.accountId}`}
              </span>
              <span className={`r ${pnlClass(r.totalPnl)} pnlbar`}>
                <i style={{ width: `${(Math.abs(r.totalPnl) / max) * 100}%` }} className={r.totalPnl >= 0 ? 'pos' : 'neg'} />
                {usd(r.totalPnl, true)}
              </span>
              <span className={`r ${pnlClass(r.realizedPnl)}`}>{usd(r.realizedPnl, true)}</span>
              <span className={`r ${pnlClass(r.unrealizedPnl)}`}>{usd(r.unrealizedPnl, true)}</span>
              <span className="r">{r.openPositions}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
