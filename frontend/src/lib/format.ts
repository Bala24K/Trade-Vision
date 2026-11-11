// All money and price math happens in integers on the server; the UI only formats.

/** 630 -> "63.0¢" */
export const cents = (ticks: number) => (ticks < 0 ? '—' : `${(ticks / 10).toFixed(1)}¢`);

/** 630 -> "63.0" (for dense tables) */
export const c1 = (ticks: number) => (ticks < 0 ? '—' : (ticks / 10).toFixed(1));

/** mills -> "$1,234.56" */
export function usd(mills: number, signed = false): string {
  const v = mills / 1000;
  const s = Math.abs(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const sign = v < 0 ? '-' : signed && v > 0 ? '+' : '';
  return `${sign}$${s}`;
}

/** Compact $ for volumes: mills -> "$12.3K" */
export function usdCompact(mills: number): string {
  const v = mills / 1000;
  if (v >= 1e6) return `$${(v / 1e6).toFixed(1)}M`;
  if (v >= 1e3) return `$${(v / 1e3).toFixed(1)}K`;
  return `$${v.toFixed(0)}`;
}

export const qty = (n: number) => n.toLocaleString('en-US');

/** Price in the order's own outcome, from YES-book ticks. */
export const outcomePrice = (yesTicks: number, outcome: 'YES' | 'NO') =>
  yesTicks < 0 ? -1 : outcome === 'YES' ? yesTicks : 1000 - yesTicks;

export function timeOf(micros: number): string {
  const d = new Date(micros / 1000);
  return d.toLocaleTimeString('en-GB', { hour12: false });
}

export const pnlClass = (v: number) => (v > 0 ? 'up' : v < 0 ? 'down' : 'flat');
