import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { c1, pnlClass, usdCompact } from '../lib/format';
import { useMarkets, useUi } from '../stores/stores';

/**
 * Virtualized: only visible rows are mounted, so the list stays smooth with thousands of markets.
 * Each row subscribes to its own market only, so a ticker update re-renders one row, not the list.
 */
const Row = memo(function Row({ id, top, height }: { id: number; top: number; height: number }) {
  const m = useMarkets((s) => s.byId[id]);
  const selected = useUi((s) => s.selected === id);
  const prev = useRef<number | null>(null);
  const [flash, setFlash] = useState('');
  const price = m ? m.stats.mark : -1;
  useEffect(() => {
    if (prev.current != null && price !== prev.current) {
      setFlash(price > prev.current ? 'flash-up' : 'flash-down');
      const t = setTimeout(() => setFlash(''), 600);
      prev.current = price;
      return () => clearTimeout(t);
    }
    prev.current = price;
  }, [price]);
  if (!m) return null;
  const s = m.stats;
  return (
    <div
      className={`mrow ${selected ? 'sel' : ''} ${flash}`}
      style={{ top, height }}
      onClick={() => useUi.getState().select(id)}
      role="option"
      aria-selected={selected}
    >
      <div className="q" title={m.question}>{m.question}</div>
      <div className="px">{c1(s.mark)}<span className="dim" style={{ fontSize: 11 }}>¢</span></div>
      <div className="meta">
        <span className={`badge ${m.source === 'POLYMARKET' ? 'poly' : 'sim'}`}>{m.source === 'POLYMARKET' ? 'POLY' : 'SIM'}</span>
        <span>{m.category}</span>
        <span>{usdCompact(s.notional)} vol</span>
        {s.status !== 'OPEN' && <span className="down">{s.status}</span>}
      </div>
      <div className={`chg ${pnlClass(s.change24h)}`}>{s.change24h > 0 ? '+' : ''}{(s.change24h / 10).toFixed(1)}</div>
    </div>
  );
});

export function MarketList() {
  const order = useMarkets((s) => s.order);
  const byId = useMarkets((s) => s.byId);
  const [q, setQ] = useState('');
  const [cat, setCat] = useState<string>('All');
  const parent = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  // Categories only change when markets are added; don't recompute on every ticker.
  const catKey = order.map((id) => byId[id]?.category).join('|');
  const categories = useMemo(() => ['All', 'Live', ...Array.from(new Set(catKey.split('|').filter(Boolean)))], [catKey]);

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return order.filter((id) => {
      const m = byId[id];
      if (!m) return false;
      if (cat === 'Live' && m.source !== 'POLYMARKET') return false;
      if (cat !== 'All' && cat !== 'Live' && m.category !== cat) return false;
      return !needle || m.question.toLowerCase().includes(needle);
    });
    // byId identity changes on every ticker; filtering only depends on question/category/source, so key on those.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [order, q, cat, catKey]);

  const rowH = 70;
  const v = useVirtualizer({ count: filtered.length, getScrollElement: () => parent.current, estimateSize: () => rowH, overscan: 6 });

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === '/' && document.activeElement?.tagName !== 'INPUT') {
        e.preventDefault();
        searchRef.current?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  return (
    <>
      <div className="panel-head">Markets<span className="spacer" /><span className="dim">{filtered.length}</span></div>
      <label className="search">
        <input ref={searchRef} placeholder="Search markets" value={q} onChange={(e) => setQ(e.target.value)}
               onKeyDown={(e) => e.key === 'Escape' && (setQ(''), (e.target as HTMLInputElement).blur())} />
        <kbd>/</kbd>
      </label>
      <div className="chips">
        {categories.map((c) => (
          <button key={c} className={`chip ${cat === c ? 'on' : ''}`} onClick={() => setCat(c)}>{c}</button>
        ))}
      </div>
      <div className="mlist" ref={parent} role="listbox" aria-label="Markets">
        {order.length === 0 ? (
          Array.from({ length: 8 }, (_, i) => <div key={i} className="skeleton" style={{ height: 48, margin: 10 }} />)
        ) : filtered.length === 0 ? (
          <div className="empty">No markets match “{q}”</div>
        ) : (
          <div style={{ height: v.getTotalSize(), position: 'relative' }}>
            {v.getVirtualItems().map((it) => (
              <Row key={filtered[it.index]} id={filtered[it.index]} top={it.start} height={rowH} />
            ))}
          </div>
        )}
      </div>
    </>
  );
}
