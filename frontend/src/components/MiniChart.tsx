import { useEffect, useRef } from 'react';
import { createChart, type IChartApi, type ISeriesApi, type UTCTimestamp, LineStyle } from 'lightweight-charts';
import type { Point } from '../lib/analytics';

export interface LineSpec {
  name: string;
  color: string;
  data: Point[];
  dashed?: boolean;
  scale?: 'left' | 'right';
  format?: (v: number) => string;
}

export interface HistSpec {
  name: string;
  data: (Point & { color?: string })[];
  color: string;
}

const fmtTime = (t: number) => new Date(t * 1000).toLocaleTimeString('en-GB', { hour12: false, hour: '2-digit', minute: '2-digit' });

/**
 * Thin wrapper over Lightweight Charts for the analytics page: the chart is created once, and only series data
 * is replaced when new buckets arrive (no teardown, no flicker on each 5 s poll).
 */
export function MiniChart({ lines = [], hists = [], height }: { lines?: LineSpec[]; hists?: HistSpec[]; height?: number }) {
  const host = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const lineSeries = useRef<Map<string, ISeriesApi<'Line'>>>(new Map());
  const histSeries = useRef<Map<string, ISeriesApi<'Histogram'>>>(new Map());

  useEffect(() => {
    if (!host.current) return;
    const c = createChart(host.current, {
      autoSize: true,
      layout: { background: { color: '#10141b' }, textColor: '#9aa4b2', fontFamily: 'JetBrains Mono, monospace', fontSize: 10.5 },
      grid: { vertLines: { color: '#161b24' }, horzLines: { color: '#161b24' } },
      rightPriceScale: { borderColor: '#1f2632' },
      leftPriceScale: { borderColor: '#1f2632', visible: lines.some((l) => l.scale === 'left') },
      timeScale: { borderColor: '#1f2632', timeVisible: true, secondsVisible: false, tickMarkFormatter: fmtTime },
      localization: { timeFormatter: fmtTime },
      handleScroll: false,
      handleScale: false,
    });
    chart.current = c;
    const ls = lineSeries.current;
    const hs = histSeries.current;
    return () => {
      c.remove();
      chart.current = null;
      ls.clear();
      hs.clear();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const c = chart.current;
    if (!c) return;
    for (const l of lines) {
      let s = lineSeries.current.get(l.name);
      if (!s) {
        s = c.addLineSeries({
          color: l.color, lineWidth: 2, priceScaleId: l.scale ?? 'right', lineStyle: l.dashed ? LineStyle.Dashed : LineStyle.Solid,
          priceLineVisible: false, lastValueVisible: true, title: l.name,
          priceFormat: l.format ? { type: 'custom', minMove: 0.01, formatter: l.format } : undefined,
        });
        lineSeries.current.set(l.name, s);
      }
      s.setData(l.data.map((p) => ({ time: p.time as UTCTimestamp, value: p.value })));
    }
    for (const h of hists) {
      let s = histSeries.current.get(h.name);
      if (!s) {
        s = c.addHistogramSeries({ color: h.color, priceFormat: { type: 'volume' }, priceLineVisible: false, title: h.name });
        histSeries.current.set(h.name, s);
      }
      s.setData(h.data.map((p) => ({ time: p.time as UTCTimestamp, value: p.value, color: p.color })));
    }
    c.timeScale().fitContent();
  }, [lines, hists]);

  return <div className="chart" ref={host} style={height ? { height } : undefined} />;
}
