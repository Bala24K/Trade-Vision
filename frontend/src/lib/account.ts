import { api } from './api';
import { useAccount } from '../stores/stores';

/** Debounced account refresh: a burst of fills produces many push notifications; one refetch is enough. */
let timer: number | undefined;

async function run() {
  try {
    const [p, h, f] = await Promise.all([api.me(), api.history(), api.fills()]);
    const s = useAccount.getState();
    s.setPortfolio(p);
    s.setHistory(h);
    s.setFills(f);
    s.setReady(true);
  } catch {
    /* transient; the next push or the periodic safety net retries */
  }
}

export const refresher = {
  now: run,
  soon: () => {
    window.clearTimeout(timer);
    timer = window.setTimeout(run, 120);
  },
};
