import { useEffect, useRef } from 'react';
import { api } from './lib/api';
import { feed } from './lib/ws';
import { useMarkets, useUi } from './stores/stores';
import { refresher } from './lib/account';
import { TopBar } from './components/TopBar';
import { MarketList } from './components/MarketList';
import { MarketHeader } from './components/MarketHeader';
import { PriceChart } from './components/PriceChart';
import { OrderBook } from './components/OrderBook';
import { OrderTicket } from './components/OrderTicket';
import { AccountTabs } from './components/AccountTabs';
import { Toasts } from './components/Toasts';
import { useShortcuts } from './components/useShortcuts';
import { AnalyticsView } from './components/AnalyticsView';
import { LeaderboardView } from './components/LeaderboardView';

export function App() {
  const selected = useUi((s) => s.selected);
  const view = useUi((s) => s.view);
  const started = useRef(false);
  useShortcuts();

  useEffect(() => {
    // Safety net: if account pushes were missed during a reconnect, converge anyway.
    const t = window.setInterval(refresher.now, 15_000);
    return () => window.clearInterval(t);
  }, []);

  useEffect(() => {
    if (started.current) return; // StrictMode double-invokes effects in dev
    started.current = true;
    (async () => {
      try {
        await api.ensureSession();
        const markets = await api.markets();
        useMarkets.getState().setAll(markets);
        feed.start(refresher.soon);
        if (markets.length) useUi.getState().select(markets[0].id);
        await refresher.now();
      } catch (e) {
        useUi.getState().toast('err', `Cannot reach the gateway: ${(e as Error).message}`);
      }
    })();
  }, []);

  useEffect(() => {
    if (selected != null) feed.focus(selected);
  }, [selected]);

  if (view !== 'trade') {
    return (
      <div className="app">
        <TopBar />
        {view === 'analytics' ? <AnalyticsView /> : <LeaderboardView />}
        <Toasts />
      </div>
    );
  }

  return (
    <div className="app">
      <TopBar />
      <div className="left panel">
        <MarketList />
      </div>
      <div className="center">
        <MarketHeader />
        <div className="panel">
          <PriceChart />
        </div>
        <div className="panel">
          <AccountTabs />
        </div>
      </div>
      <div className="right">
        <div className="panel">
          <OrderBook />
        </div>
        <div className="panel">
          <OrderTicket />
        </div>
      </div>
      <Toasts />
    </div>
  );
}
