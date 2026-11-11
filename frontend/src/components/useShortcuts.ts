import { useEffect } from 'react';
import { api } from '../lib/api';
import { refresher } from '../lib/account';
import { useMarkets, useUi } from '../stores/stores';

/**
 * Keyboard-first trading. Ignored while typing in an input (except Esc).
 *   Y / N   outcome        B / S   buy / sell        P   focus price     Q   focus quantity
 *   J / K   next / previous market                    Esc cancel all my orders in this market
 *   Alt+T / Alt+A / Alt+L   Trade / Analytics / Leaderboard view
 */
export function useShortcuts() {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const typing = (e.target as HTMLElement)?.tagName === 'INPUT';
      const ui = useUi.getState();
      if (e.key === 'Escape') {
        if (typing) {
          (e.target as HTMLElement).blur();
          return;
        }
        if (ui.selected != null && ui.view === 'trade') {
          api.cancelAll(ui.selected).then(() => {
            ui.toast('info', 'Cancelled all your orders in this market');
            refresher.now();
          }).catch(() => {});
        }
        return;
      }
      if (e.altKey && !typing) {
        const v = ({ t: 'trade', a: 'analytics', l: 'leaderboard' } as const)[e.key.toLowerCase() as 't' | 'a' | 'l'];
        if (v) {
          e.preventDefault();
          ui.setView(v);
        }
        return;
      }
      if (typing || e.metaKey || e.ctrlKey || ui.view !== 'trade') return;
      const order = useMarkets.getState().order;
      switch (e.key.toLowerCase()) {
        case 'y': ui.setTicket({ ticketOutcome: 'YES', ticketPrice: '' }); break;
        case 'n': ui.setTicket({ ticketOutcome: 'NO', ticketPrice: '' }); break;
        case 'b': ui.setTicket({ ticketAction: 'BUY' }); break;
        case 's': ui.setTicket({ ticketAction: 'SELL' }); break;
        case 'p': e.preventDefault(); document.getElementById('ticket-price')?.focus(); break;
        case 'q': e.preventDefault(); document.getElementById('ticket-qty')?.focus(); break;
        case 'j':
        case 'k': {
          if (!order.length) break;
          const i = Math.max(0, order.indexOf(ui.selected ?? order[0]));
          const next = order[(i + (e.key.toLowerCase() === 'j' ? 1 : order.length - 1)) % order.length];
          ui.select(next);
          break;
        }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}
