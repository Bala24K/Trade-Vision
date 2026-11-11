import { useEffect, useMemo, useState } from 'react';
import { api } from '../lib/api';
import { WINDOWS, centsOr, compact, imbalanceLabel, pct, series, signedCents } from '../lib/analytics';
import type { MarketAnalytics, ScannerRow } from '../lib/types';
import { useUi } from '../stores/stores';
import { MiniChart, type HistSpec, type LineSpec } from './MiniChart';

/** Poll a loader every `ms` while mounted; keeps the last good value on transient errors. */
function usePoll<T>(load: () => Promise<T>, ms: number, deps: unknown[]): T | null {
  const [v, setV] = useState<T | null>(null);
  useEffect(() => {
    let alive = true;
    const run = () => load().then((x) => alive && setV(x)).catch(() => {});
    setV(null);
    run();
    const t = window.setInterval(run, ms);
    return () => {
      alive = false;
      window.clearInterval(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return v;
}

type SortKey = 'volume' | 'vol' | 'spread' | 'imbalance' | 'basis';

function Scanner({ selected }: { selected: number | null }) {
  const rows = usePoll(api.scanner, 5000, []);
  const [sort, setSort] = useState<SortKey>('volume');
  const sorted = useMemo(() => {
    const val = (r: ScannerRow): number => {
      switch (sort) {
        case 'volume': return r.metrics.volume;
        case 'vol': return r.metrics.realizedVolCentsPerHour ?? -1;
        case 'spread': return -(r.live.spread ?? 1e9);
        case 'imbalance': return Math.abs(r.live.imbalance ?? 0);
        case 'basis': return Math.abs(r.metrics.refBasisCents ?? -1);
      }
    };
    return [...(rows ?? [])].sort((a, b) => val(b) - val(a));
  }, [rows, sort]);
  const cols = { gridTemplateColumns: 'minmax(120px,1fr) 58px 62px 54px 64px 62px' };
  const th = (k: SortKey, label: string, title: string) => (
    <button className={`th-sort r ${sort === k ? 'on' : ''}`} onClick={() => setSort(k)} title={title}>{label}</button>
  );
  return (
    <>
      <div className="panel-head">Market scanner · last hour</div>
      <div className="table">
        <div className="tr th" style={cols}>
          <span>Market</span>
          {th('volume', 'Vol', 'Contracts traded')}
          {th('vol', 'σ/h', 'Realized volatility, cents per √hour')}
          {th('spread', 'Sprd', 'Current spread')}
          {th('imbalance', 'Imb', 'Top-5 depth imbalance')}
          {th('basis', 'Basis', 'Average trade price minus Polymarket mid')}
        </div>
        {!rows && <div className="empty">Loading…</div>}
        {sorted.map((r) => (
          <div key={r.marketId} className={`tr click ${selected === r.marketId ? 'sel' : ''}`} style={cols}
               onClick={() => useUi.getState().select(r.marketId)}>
            <span className="q" title={r.question}>
              {r.marketSource === 'POLYMARKET' && <span className="badge poly">PM</span>} {r.question}
            </span>
            <span className="r">{compact(r.metrics.volume)}</span>
            <span className="r">{r.metrics.realizedVolCentsPerHour == null ? '—' : r.metrics.realizedVolCentsPerHour.toFixed(2)}</span>
            <span className="r">{centsOr(r.live.spread)}</span>
            <span className={`r ${(r.live.imbalance ?? 0) > 0.05 ? 'up' : (r.live.imbalance ?? 0) < -0.05 ? 'down' : 'flat'}`}>
              {r.live.imbalance == null ? '—' : `${(r.live.imbalance * 100).toFixed(0)}%`}
            </span>
            <span className="r">{r.metrics.refBasisCents == null ? '—' : signedCents(r.metrics.refBasisCents, 1)}</span>
          </div>
        ))}
      </div>
    </>
  );
}

function Tile({ label, value, sub, cls, title }: { label: string; value: string; sub?: string; cls?: string; title?: string }) {
  return (
    <div className="tile" title={title}>
      <label>{label}</label>
      <b className={cls}>{value}</b>
      {sub && <small>{sub}</small>}
    </div>
  );
}

const cents2 = (v: number) => `${v.toFixed(1)}¢`;

function Detail({ a }: { a: MarketAnalytics }) {
  const m = a.metrics;
  const charts = useMemo(() => {
    const price: LineSpec[] = [
      { name: 'Close', color: '#e6eaf0', data: series(a.buckets, (b) => b.close, 0.1), format: cents2 },
      { name: 'VWAP', color: '#f5a524', data: series(a.buckets, (b) => b.vwap, 0.1), format: cents2 },
      { name: 'Polymarket', color: '#8fb0ff', data: series(a.buckets, (b) => b.refMid, 0.1), dashed: true, format: cents2 },
    ];
    const flow: HistSpec[] = [
      { name: 'Buy', color: 'rgba(34,197,94,0.7)', data: series(a.buckets, (b) => (b.trades ? b.buyVolume : null)) },
      { name: 'Sell', color: 'rgba(242,85,90,0.7)', data: series(a.buckets, (b) => (b.trades ? -b.sellVolume : null)) },
    ];
    const micro: LineSpec[] = [
      { name: 'Spread ¢', color: '#c084fc', data: series(a.buckets, (b) => b.spread, 0.1), format: (v) => v.toFixed(1) },
      { name: 'Imbalance %', color: '#22d3ee', data: series(a.buckets, (b) => b.imbalance, 100), scale: 'left', format: (v) => v.toFixed(0) },
    ];
    return { price, flow, micro };
  }, [a]);
  const live = a.live;
  return (
    <>
      <div className="tiles">
        <Tile label="VWAP" value={centsOr(m.vwap == null ? null : m.vwap, 2)} sub={`last ${centsOr(m.last)}`}
              title="Volume-weighted average trade price over the window" />
        <Tile label="Range" value={m.low == null ? '—' : `${centsOr(m.low)} – ${centsOr(m.high)}`} sub={`open ${centsOr(m.open)}`} />
        <Tile label="Volume" value={compact(m.volume)} sub={`${m.trades.toLocaleString()} trades · avg ${m.avgTradeSize?.toFixed(0) ?? '—'}`} />
        <Tile label="Buy share" value={pct(m.buyShare)} cls={(m.buyShare ?? 0.5) >= 0.5 ? 'up' : 'down'}
              sub={`${compact(m.buyVolume)} / ${compact(m.sellVolume)}`} title="Share of volume where the taker bought YES" />
        <Tile label="Realized vol" value={m.realizedVolCentsPerHour == null ? '—' : `${m.realizedVolCentsPerHour.toFixed(2)}¢`}
              sub="per √hour" title="Std-dev of close-to-close moves, scaled to one hour" />
        <Tile label="Spread" value={centsOr(live.spread)} sub={`avg ${centsOr(m.avgSpread, 2)}`} />
        <Tile label="Imbalance" value={imbalanceLabel(live.imbalance)} sub={`avg ${imbalanceLabel(m.avgImbalance)}`}
              cls={(live.imbalance ?? 0) > 0 ? 'up' : (live.imbalance ?? 0) < 0 ? 'down' : 'flat'}
              title="(bid depth − ask depth) / total, best 5 levels" />
        <Tile label="Polymarket basis" value={signedCents(m.refBasisCents)} sub={live.refMid == null ? 'no reference' : `ref ${centsOr(live.refMid)}`}
              title="How far our trades are from the Polymarket mid, on average" />
      </div>
      <div className="acharts">
        <div className="panel">
          <div className="panel-head">Price · close vs VWAP vs Polymarket</div>
          <MiniChart lines={charts.price} />
        </div>
        <div className="panel">
          <div className="panel-head">Order flow · taker buys vs sells</div>
          <MiniChart hists={charts.flow} />
        </div>
        <div className="panel">
          <div className="panel-head">Microstructure · spread &amp; depth imbalance</div>
          <MiniChart lines={charts.micro} />
        </div>
      </div>
    </>
  );
}

export function AnalyticsView() {
  const selected = useUi((s) => s.selected);
  const [win, setWin] = useState(60);
  const a = usePoll(() => (selected == null ? Promise.resolve(null) : api.analytics(selected, win)), 5000, [selected, win]);
  return (
    <div className="view aview">
      <div className="panel">
        <Scanner selected={selected} />
      </div>
      <div className="amain">
        <div className="mhead">
          <h1>{a?.question ?? 'Select a market'}</h1>
          <div className="tfs">
            {WINDOWS.map(([label, mins]) => (
              <button key={label} className={`tf ${win === mins ? 'on' : ''}`} onClick={() => setWin(mins)}>{label}</button>
            ))}
          </div>
          {a && (
            <span className={`pill src ${a.source.startsWith('clickhouse') ? 'live' : ''}`}
                  title={a.source === 'clickhouse' ? 'Aggregated in ClickHouse over the trades and book-sample tables' : 'Served from the in-memory 24h store'}>
              <span className="dot" />{a.source} · {a.bucketSeconds / 60}m buckets
            </span>
          )}
        </div>
        {a ? <Detail a={a} /> : <div className="empty">Loading analytics…</div>}
      </div>
    </div>
  );
}
