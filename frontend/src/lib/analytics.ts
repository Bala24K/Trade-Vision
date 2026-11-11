// Pure helpers for the analytics, leaderboard and history views (unit-tested in analytics.test.ts).
import type { BucketDto, OrderRow, OrderView } from './types';

export interface Point {
  time: number;
  value: number;
}

/** Bucket field -> chart points, skipping empty buckets (null) instead of drawing fake zeros. */
export function series(buckets: BucketDto[], pick: (b: BucketDto) => number | null, scale = 1): Point[] {
  const out: Point[] = [];
  for (const b of buckets) {
    const v = pick(b);
    if (v != null && Number.isFinite(v)) out.push({ time: b.t, value: v * scale });
  }
  return out;
}

/** Ticks (0.1¢) -> "52.3¢"; null -> "—". */
export const centsOr = (ticks: number | null | undefined, digits = 1) =>
  ticks == null ? '—' : `${(ticks / 10).toFixed(digits)}¢`;

/** Already in cents, signed: 0.5 -> "+0.50¢". */
export const signedCents = (c: number | null | undefined, digits = 2) =>
  c == null ? '—' : `${c > 0 ? '+' : c < 0 ? '−' : ''}${Math.abs(c).toFixed(digits)}¢`;

export const pct = (x: number | null | undefined, digits = 0) => (x == null ? '—' : `${(x * 100).toFixed(digits)}%`);

/** Imbalance in [-1, 1] -> "+24% bid" / "−10% ask". */
export function imbalanceLabel(x: number | null | undefined): string {
  if (x == null) return '—';
  if (Math.abs(x) < 0.005) return 'balanced';
  return `${x > 0 ? '+' : '−'}${Math.abs(x * 100).toFixed(0)}% ${x > 0 ? 'bid' : 'ask'}`;
}

export const compact = (n: number) =>
  n >= 1e6 ? `${(n / 1e6).toFixed(1)}M` : n >= 1e3 ? `${(n / 1e3).toFixed(1)}K` : `${Math.round(n)}`;

/** History rows come from Postgres (OrderRow) or memory (OrderView); render both the same way. */
export function normalizeOrder(o: OrderRow | OrderView): OrderView {
  if ('filledValue' in o) return o;
  const { avgPriceMilli, ...rest } = o;
  return { ...rest, filledValue: Math.round((avgPriceMilli * o.filled) / 1000) };
}

/** Merge pages newest-first without duplicates (live refreshes and older pages can overlap). */
export function mergeNewestFirst<T>(a: T[], b: T[], key: (t: T) => number): T[] {
  const seen = new Map<number, T>();
  for (const x of a) seen.set(key(x), x);
  for (const x of b) if (!seen.has(key(x))) seen.set(key(x), x);
  return [...seen.values()].sort((x, y) => key(y) - key(x));
}

export const WINDOWS: [string, number][] = [
  ['1H', 60],
  ['6H', 360],
  ['24H', 1440],
  ['7D', 10080],
];
