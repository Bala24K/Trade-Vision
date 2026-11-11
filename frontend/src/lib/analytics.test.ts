import { describe, expect, it } from 'vitest';
import { centsOr, imbalanceLabel, mergeNewestFirst, normalizeOrder, series, signedCents } from './analytics';
import type { BucketDto, OrderRow } from './types';

const b = (t: number, close: number | null, vwap: number | null): BucketDto => ({
  t, open: close, high: close, low: close, close, volume: close ? 10 : 0, buyVolume: 5, sellVolume: 5, trades: close ? 1 : 0,
  vwap, spread: null, imbalance: null, refMid: null,
});

describe('analytics helpers', () => {
  it('series skips empty buckets instead of plotting zeros', () => {
    const pts = series([b(0, 500, 501), b(60, null, null), b(120, 510, 509)], (x) => x.close, 0.1);
    expect(pts).toEqual([{ time: 0, value: 50 }, { time: 120, value: 51 }]);
  });

  it('formats cents, signed basis and imbalance', () => {
    expect(centsOr(523)).toBe('52.3¢');
    expect(centsOr(null)).toBe('—');
    expect(signedCents(0.5)).toBe('+0.50¢');
    expect(signedCents(-1.25)).toBe('−1.25¢');
    expect(imbalanceLabel(0.24)).toBe('+24% bid');
    expect(imbalanceLabel(-0.1)).toBe('−10% ask');
    expect(imbalanceLabel(0.001)).toBe('balanced');
  });

  it('normalizes Postgres order rows to the in-memory shape', () => {
    const row: OrderRow = {
      orderId: 9, clientOrderId: 1, marketId: 2, outcome: 'YES', action: 'BUY', tif: 'GTC', limitPrice: 600, quantity: 10,
      filled: 4, avgPriceMilli: 595_500, status: 'PARTIALLY_FILLED', reason: null, createdTs: 1, updatedTs: 2,
    };
    const v = normalizeOrder(row);
    expect(v.filledValue).toBe(2382); // 595.5 ticks * 4
    expect('avgPriceMilli' in v).toBe(false);
  });

  it('merges pages newest-first without duplicates', () => {
    const merged = mergeNewestFirst([{ id: 5 }, { id: 4 }], [{ id: 4 }, { id: 3 }, { id: 1 }], (x) => x.id);
    expect(merged.map((x) => x.id)).toEqual([5, 4, 3, 1]);
  });
});
