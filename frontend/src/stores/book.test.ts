import { beforeEach, describe, expect, it } from 'vitest';
import { useBook } from './stores';
import { cents, outcomePrice, usd } from '../lib/format';

const snap = (seq: number, bids: number[][], asks: number[][]) => ({ marketId: 1, snapshot: true, seq, prevSeq: 0, bids, asks });
const delta = (seq: number, prevSeq: number, bids: number[][], asks: number[][] = []) => ({ marketId: 1, snapshot: false, seq, prevSeq, bids, asks });

describe('order book sequencing', () => {
  beforeEach(() => useBook.getState().reset(1));

  it('applies a snapshot then contiguous deltas', () => {
    const b = useBook.getState();
    expect(b.apply(snap(10, [[630, 100]], [[640, 50]]))).toBe(true);
    expect(b.apply(delta(12, 10, [[630, 0], [620, 30]]))).toBe(true);
    const s = useBook.getState();
    expect(s.seq).toBe(12);
    expect([...s.bids.entries()]).toEqual([[620, 30]]);
    expect(s.asks.get(640)).toBe(50);
  });

  it('ignores deltas older than the snapshot', () => {
    const b = useBook.getState();
    b.apply(snap(10, [[630, 100]], []));
    expect(b.apply(delta(9, 5, [[630, 1]]))).toBe(true);
    expect(useBook.getState().bids.get(630)).toBe(100);
  });

  it('reports a gap so the caller can resubscribe', () => {
    const b = useBook.getState();
    b.apply(snap(10, [[630, 100]], []));
    expect(b.apply(delta(15, 13, [[630, 1]]))).toBe(false);
    expect(useBook.getState().bids.get(630)).toBe(100);
  });

  it('drops messages for another market', () => {
    expect(useBook.getState().apply({ ...snap(3, [[1, 1]], []), marketId: 2 })).toBe(true);
    expect(useBook.getState().bids.size).toBe(0);
  });
});

describe('formatting', () => {
  it('formats ticks and mills without float drift', () => {
    expect(cents(630)).toBe('63.0¢');
    expect(cents(-1)).toBe('—');
    expect(usd(1_234_560)).toBe('$1,234.56');
    expect(usd(-500, true)).toBe('-$0.50');
    expect(usd(500, true)).toBe('+$0.50');
  });

  it('converts YES-book prices to NO prices', () => {
    expect(outcomePrice(630, 'NO')).toBe(370);
    expect(outcomePrice(630, 'YES')).toBe(630);
  });
});
