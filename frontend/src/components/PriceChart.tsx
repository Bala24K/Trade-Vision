import { useEffect, useRef, useState } from 'react';
import { createChart, type IChartApi, type ISeriesApi, type IPriceLine, type UTCTimestamp, CrosshairMode } from 'lightweight-charts';
import { api } from '../lib/api';
import type { Candle, TapeEntry } from '../lib/types';
import { useMarkets, useTape, useUi } from '../stores/stores';

const TF: Record<string, number> = { '5s': 5, '1m': 60, '15m': 900 };
const toCents = (ticks: number) => ticks / 10;

/**
 * Candles load once per (market, timeframe) over REST, then update incrementally from the live trade stream:
 * each trade folds into the current bucket client-side via series.update(), no refetch, no full redraw.
 */
export function PriceChart() {
  const id = useUi((s) => s.selected);
  const [tf, setTf] = useState('5s');
  const host = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const candles = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const volume = useRef<ISeriesApi<'Histogram'> | null>(null);
  const refLine = useRef<IPriceLine | null>(null);
  const last = useRef<Candle | null>(null);

  // create once
  useEffect(() => {
    if (!host.current) return;
    const c = createChart(host.current, {
      autoSize: true,
      layout: { background: { color: '#10141b' }, textColor: '#9aa4b2', fontFamily: 'JetBrains Mono, monospace', fontSize: 11 },
      grid: { vertLines: { color: '#161b24' }, horzLines: { color: '#161b24' } },
      rightPriceScale: { borderColor: '#1f2632' },
      // The library works in UTC; show the trader's local clock instead.
      timeScale: {
        borderColor: '#1f2632', timeVisible: true, secondsVisible: true,
        tickMarkFormatter: (t: number) => new Date(t * 1000).toLocaleTimeString('en-GB', { hour12: false }),
      },
      crosshair: { mode: CrosshairMode.Normal },
      localization: { timeFormatter: (t: number) => new Date(t * 1000).toLocaleTimeString('en-GB', { hour12: false }) },
    });
    candles.current = c.addCandlestickSeries({
      upColor: '#22c55e', downColor: '#f2555a', borderVisible: false, wickUpColor: '#22c55e', wickDownColor: '#f2555a',
      priceFormat: { type: 'custom', minMove: 0.1, formatter: (p: number) => `${p.toFixed(1)}¢` },
    });
    volume.current = c.addHistogramSeries({ priceScaleId: 'vol', priceFormat: { type: 'volume' }, color: '#2a3342' });
    c.priceScale('vol').applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
    chart.current = c;
    return () => {
      c.remove();
      chart.current = null;
    };
  }, []);

  // load history on market / timeframe change
  useEffect(() => {
    if (id == null) return;
    let cancelled = false;
    api.candles(id, tf).then((cs) => {
      if (cancelled || !candles.current || !volume.current) return;
      candles.current.setData(cs.map((c) => ({ time: c.time as UTCTimestamp, open: toCents(c.open), high: toCents(c.high), low: toCents(c.low), close: toCents(c.close) })));
      volume.current.setData(cs.map((c) => ({ time: c.time as UTCTimestamp, value: c.volume, color: c.close >= c.open ? 'rgba(34,197,94,0.35)' : 'rgba(242,85,90,0.35)' })));
      last.current = cs.length ? { ...cs[cs.length - 1] } : null;
      chart.current?.timeScale().scrollToRealTime();
    }).catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [id, tf]);

  // live updates from the trade stream
  useEffect(() => {
    const interval = TF[tf];
    const onTrades = (trades: TapeEntry[]) => {
      if (!candles.current || !volume.current) return;
      for (const t of trades) {
        const bucket = Math.floor(t.ts / 1e6 / interval) * interval;
        const cur = last.current;
        if (cur && bucket < cur.time) continue;
        const c: Candle = cur && cur.time === bucket
          ? { ...cur, high: Math.max(cur.high, t.price), low: Math.min(cur.low, t.price), close: t.price, volume: cur.volume + t.quantity }
          : { time: bucket, open: t.price, high: t.price, low: t.price, close: t.price, volume: t.quantity };
        last.current = c;
        candles.current.update({ time: c.time as UTCTimestamp, open: toCents(c.open), high: toCents(c.high), low: toCents(c.low), close: toCents(c.close) });
        volume.current.update({ time: c.time as UTCTimestamp, value: c.volume, color: c.close >= c.open ? 'rgba(34,197,94,0.35)' : 'rgba(242,85,90,0.35)' });
      }
    };
    const listeners = useTape.getState().listeners;
    listeners.add(onTrades);
    return () => {
      listeners.delete(onTrades);
    };
  }, [tf]);

  // Polymarket reference mid as a dashed price line
  const refMid = useMarkets((s) => (id == null ? null : s.byId[id]?.reference?.mid ?? null));
  useEffect(() => {
    const series = candles.current;
    if (!series) return;
    if (refLine.current) {
      series.removePriceLine(refLine.current);
      refLine.current = null;
    }
    if (refMid != null && refMid > 0) {
      refLine.current = series.createPriceLine({ price: toCents(refMid), color: '#8fb0ff', lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: 'Polymarket' });
    }
  }, [refMid, id]);

  return (
    <>
      <div className="panel-head">
        Price · YES
        <div className="tfs">
          {Object.keys(TF).map((k) => (
            <button key={k} className={`tf ${tf === k ? 'on' : ''}`} onClick={() => setTf(k)}>{k}</button>
          ))}
        </div>
        <span className="spacer" />
        {refMid != null && <span style={{ color: '#8fb0ff', textTransform: 'none', letterSpacing: 0 }}>– – Polymarket mid</span>}
      </div>
      <div className="chart" ref={host} />
    </>
  );
}
