package io.tradevision.gateway.read;

import io.tradevision.engine.Market;
import io.tradevision.engine.MatchingEngine;
import io.tradevision.engine.Side;
import io.tradevision.engine.account.Account;
import io.tradevision.engine.account.Position;
import io.tradevision.engine.book.Order;
import io.tradevision.engine.book.OrderBook;
import io.tradevision.gateway.engine.Event;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CQRS read side. Engine state is owned by the matcher thread and never read from elsewhere; everything
 * HTTP and WebSocket clients see comes from here, rebuilt from the (durable) event stream.
 */
@Component
public class ReadModel {
    private final ConcurrentHashMap<Long, MarketState> markets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AccountState> accounts = new ConcurrentHashMap<>();

    public MarketState market(long id) { return markets.get(id); }
    public AccountState account(long id) { return accounts.get(id); }
    public Collection<MarketState> markets() { return markets.values(); }
    public Collection<AccountState> accounts() { return accounts.values(); }

    private MarketState marketOrCreate(long id) { return markets.computeIfAbsent(id, MarketState::new); }
    private AccountState accountOrCreate(long id) { return accounts.computeIfAbsent(id, AccountState::new); }

    /**
     * Initialise from a quiescent engine right after recovery (before the matcher thread starts). Snapshots
     * hold state but not history, so trade tapes and candles only cover the journal tail that was replayed.
     */
    public void seed(MatchingEngine e) {
        for (long id : e.markets().sortedKeys()) {
            Market m = e.market(id);
            MarketState ms = marketOrCreate(id);
            ms.seedStats(m.status(), m.winner(), m.lastTradePrice(), m.volume(), m.notional(), m.tradeCount());
            OrderBook book = m.book();
            for (Side side : Side.values()) {
                for (int p = book.best(side); p != -1; p = book.nextLevel(side, p)) {
                    ms.level(side, p, book.quantityAt(side, p));
                }
                for (int p = book.best(side); p != -1; p = book.nextLevel(side, p)) {
                    for (Order o = book.level(side, p).head(); o != null; o = o.next()) {
                        accountOrCreate(o.accountId).seedOrder(new AccountState.OrderView(o.orderId, o.clientOrderId,
                                o.marketId, o.outcome, o.action, o.tif, o.limitPrice, o.quantity, o.filled(), 0,
                                o.filled() > 0 ? AccountState.OrderStatus.PARTIALLY_FILLED : AccountState.OrderStatus.OPEN,
                                null, o.timestamp, o.timestamp));
                    }
                }
            }
        }
        for (long id : e.accounts().sortedKeys()) {
            Account a = e.account(id);
            AccountState as = accountOrCreate(id);
            as.balance(a.available(), a.reserved());
            for (long mid : a.positions().sortedKeys()) {
                Position p = a.position(mid);
                as.position(new AccountState.PositionView(mid, p.yes(), p.no(), p.yesLocked(), p.noLocked(), p.yesCost(),
                        p.noCost(), p.realizedPnl()));
            }
        }
    }

    /** Replay path: only trades are meaningful (state is seeded from the engine afterwards). */
    public void replayTrade(Event.Trade t) {
        marketOrCreate(t.marketId()).trade(t);
    }

    /** Apply one durable command's events. Returns nothing; callers fan out the same list to other sinks. */
    public void apply(List<Event> events) {
        for (Event ev : events) apply(ev);
    }

    /** Book sequence of the last applied level change per event (exposed for WS delta ordering). */
    public long apply(Event ev) {
        switch (ev) {
            case Event.MarketCreated e -> marketOrCreate(e.marketId());
            case Event.MarketStatusChanged e -> marketOrCreate(e.marketId()).status(e.status(), e.winner());
            case Event.AccountCreated e -> accountOrCreate(e.accountId());
            case Event.Balance e -> accountOrCreate(e.accountId()).balance(e.available(), e.reserved());
            case Event.PositionUpdate e -> accountOrCreate(e.accountId()).position(new AccountState.PositionView(e.marketId(),
                    e.yes(), e.no(), e.yesLocked(), e.noLocked(), e.yesCost(), e.noCost(), e.realizedPnl()));
            case Event.OrderAccepted e -> accountOrCreate(e.accountId()).accepted(e);
            case Event.Trade t -> {
                marketOrCreate(t.marketId()).trade(t);
                accountOrCreate(t.makerAccountId()).fill(t.makerOrderId(), t.tradeId(), t.marketId(), t.makerOutcome(),
                        t.makerAction(), t.price(), t.quantity(), true, t.ts());
                accountOrCreate(t.takerAccountId()).fill(t.takerOrderId(), t.tradeId(), t.marketId(), t.takerOutcome(),
                        t.takerAction(), t.price(), t.quantity(), false, t.ts());
            }
            case Event.OrderClosed e -> accountOrCreate(e.accountId()).closed(e);
            case Event.BookLevel e -> {
                return marketOrCreate(e.marketId()).level(e.side(), e.price(), e.quantity());
            }
            case Event.Settlement e -> accountOrCreate(e.accountId()).settled(e);
            case Event.OrderRejected e -> { }
            case Event.CancelRejected e -> { }
            case Event.CommandRejected e -> { }
        }
        return -1;
    }
}
