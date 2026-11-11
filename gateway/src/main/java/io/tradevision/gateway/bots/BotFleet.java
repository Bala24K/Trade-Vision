package io.tradevision.gateway.bots;

import io.tradevision.engine.Action;
import io.tradevision.engine.CommandType;
import io.tradevision.engine.MarketStatus;
import io.tradevision.engine.Outcome;
import io.tradevision.engine.TimeInForce;
import io.tradevision.gateway.TvProperties;
import io.tradevision.gateway.catalog.MarketCatalog;
import io.tradevision.gateway.engine.EngineHost;
import io.tradevision.gateway.ingest.PolymarketFeed;
import io.tradevision.gateway.ingest.ReferenceBook;
import io.tradevision.gateway.read.AccountState;
import io.tradevision.gateway.read.MarketState;
import io.tradevision.gateway.read.ReadModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Liquidity and flow for the paper venue: one market maker and three noise traders per market.
 * They trade through exactly the same engine path as humans (no back doors), so they also exercise the
 * engine, the durability gate and the WebSocket fan-out continuously.
 *
 * <ul>
 *   <li><b>Fair value.</b> Polymarket mirrors anchor to the live Polymarket mid, so our paper market tracks the
 *       real one. Synthetic markets follow a mean-reverting random walk with rare "news" jumps.</li>
 *   <li><b>Market maker.</b> Quotes a 5-level ladder on both sides. Bids are BUY YES; asks are BUY NO at the complement,
 *       so the maker never needs inventory (that is the unified YES/NO book at work). It only re-quotes when fair value
 *       moves or its quotes get hit, which preserves queue priority and keeps command volume sane.</li>
 *   <li><b>Noise traders.</b> Mostly small marketable IOC orders (they create trades and move the tape), some
 *       passive limits, and they sell back inventory so positions stay bounded.</li>
 * </ul>
 */
@Component
public class BotFleet implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BotFleet.class);
    static final long MM_BASE = 1_000_000, NOISE_BASE = 2_000_000;
    private static final int NOISE_PER_MARKET = 3;
    private static final long FUNDING = 10_000_000L * 1000; // $10M each

    private final boolean enabled;
    private final EngineHost engine;
    private final ReadModel readModel;
    private final MarketCatalog catalog;
    private final PolymarketFeed polymarket;
    private final SplittableRandom rnd = new SplittableRandom(20261111);
    private final Map<Long, MarketBot> bots = new HashMap<>();
    private final AtomicLong coid = new AtomicLong(System.currentTimeMillis() * 1000);
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public BotFleet(TvProperties props, EngineHost engine, ReadModel readModel, MarketCatalog catalog, PolymarketFeed polymarket) {
        this.enabled = props.bots().enabled();
        this.engine = engine;
        this.readModel = readModel;
        this.catalog = catalog;
        this.polymarket = polymarket;
    }

    private final class MarketBot {
        final long marketId;
        final long mm;
        final boolean synthetic;
        double fair, anchor;
        int quotedFair = -1;
        int quotedOrders;
        CompletableFuture<?> inFlight = CompletableFuture.completedFuture(null);
        int ticksSinceQuote;

        MarketBot(MarketCatalog.MarketMeta meta) {
            this.marketId = meta.id();
            this.mm = MM_BASE + meta.id();
            this.synthetic = meta.source() == MarketCatalog.Source.SYNTHETIC;
            MarketState st = readModel.market(meta.id());
            int start = st != null && st.stats().last() > 0 ? st.stats().last() : meta.seedPrice() > 0 ? meta.seedPrice() : 500;
            fair = anchor = start;
        }

        void step() {
            MarketState st = readModel.market(marketId);
            if (st == null || st.status() != MarketStatus.OPEN) return;
            updateFair();
            quote();
            for (int k = 0; k < NOISE_PER_MARKET; k++) noise(NOISE_BASE + marketId * 10 + k);
        }

        void updateFair() {
            Optional<ReferenceBook.Quote> ref = synthetic ? Optional.empty() : polymarket.reference(marketId);
            if (ref.isPresent()) {
                fair += 0.35 * (ref.get().mid() - fair) + gauss(0.6);
            } else {
                anchor = clamp(anchor + gauss(0.9), 30, 970);
                fair += 0.08 * (anchor - fair) + gauss(1.6);
                if (synthetic && rnd.nextInt(1500) == 0) { // rare news shock
                    double jump = (rnd.nextBoolean() ? 1 : -1) * (40 + rnd.nextInt(90));
                    anchor = clamp(anchor + jump, 30, 970);
                    fair = clamp(fair + jump * 0.6, 20, 980);
                }
            }
            fair = clamp(fair, 15, 985);
        }

        void quote() {
            ticksSinceQuote++;
            if (!inFlight.isDone()) return; // never pile up re-quotes behind a slow engine
            AccountState acct = readModel.account(mm);
            if (acct == null) return;
            var open = acct.openOrders(marketId);
            int f = (int) Math.round(fair);
            boolean moved = quotedFair < 0 || Math.abs(f - quotedFair) >= 3;
            boolean hit = open.size() < quotedOrders;
            if (!moved && !hit && ticksSinceQuote < 40) return;

            CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
            for (AccountState.OrderView o : open) chain = engine.submit(c -> c.cancelOrder(mm, o.orderId()));
            int half = synthetic ? 4 + rnd.nextInt(4) : 3 + rnd.nextInt(3);
            int step = synthetic ? 3 : 2;
            int placed = 0;
            for (int i = 0; i < 5; i++) {
                int bid = f - half - i * step;
                int ask = f + half + i * step;
                long size = (60 + rnd.nextInt(120)) * (i + 1L);
                if (bid >= 1) {
                    long c1 = coid.incrementAndGet();
                    chain = engine.submit(c -> c.placeOrder(mm, c1, marketId, Outcome.YES, Action.BUY, TimeInForce.GTC, bid, size));
                    placed++;
                }
                if (ask <= 999) {
                    long c2 = coid.incrementAndGet();
                    chain = engine.submit(c -> c.placeOrder(mm, c2, marketId, Outcome.NO, Action.BUY, TimeInForce.GTC, 1000 - ask, size));
                    placed++;
                }
            }
            inFlight = chain;
            quotedFair = f;
            quotedOrders = placed;
            ticksSinceQuote = 0;
        }

        void noise(long trader) {
            if (rnd.nextInt(100) >= 9) return;
            AccountState acct = readModel.account(trader);
            if (acct == null) return;
            int f = (int) Math.round(fair);
            long id = coid.incrementAndGet();
            // Sell back inventory sometimes so bot positions stay bounded.
            var pos = acct.snapshot().positions().stream().filter(p -> p.marketId() == marketId).findFirst();
            if (pos.isPresent() && rnd.nextInt(3) == 0) {
                var p = pos.get();
                long freeYes = p.yes() - p.yesLocked(), freeNo = p.no() - p.noLocked();
                if (freeYes > 0) {
                    long q = Math.max(1, Math.min(freeYes, 1 + rnd.nextInt(60)));
                    engine.submit(c -> c.placeOrder(trader, id, marketId, Outcome.YES, Action.SELL, TimeInForce.IOC, Math.max(1, f - 15), q));
                    return;
                }
                if (freeNo > 0) {
                    long q = Math.max(1, Math.min(freeNo, 1 + rnd.nextInt(60)));
                    engine.submit(c -> c.placeOrder(trader, id, marketId, Outcome.NO, Action.SELL, TimeInForce.IOC, Math.max(1, 1000 - f - 15), q));
                    return;
                }
            }
            long qty = Math.round(Math.exp(rnd.nextDouble(Math.log(3), Math.log(90))));
            boolean yes = rnd.nextBoolean();
            if (rnd.nextInt(100) < 72) {
                int limit = yes ? Math.min(999, f + 15) : Math.min(999, 1000 - f + 15);
                engine.submit(c -> c.placeOrder(trader, id, marketId, yes ? Outcome.YES : Outcome.NO, Action.BUY, TimeInForce.IOC, limit, qty));
            } else {
                int off = 1 + rnd.nextInt(8);
                int limit = yes ? Math.max(1, f - off) : Math.max(1, 1000 - f - off);
                engine.submit(c -> c.placeOrder(trader, id, marketId, yes ? Outcome.YES : Outcome.NO, Action.BUY, TimeInForce.GTC, limit, qty * 2));
                // Passive orders don't live forever.
                for (AccountState.OrderView o : acct.openOrders(marketId)) {
                    if (System.currentTimeMillis() * 1000 - o.createdTs() > 20_000_000L) engine.submit(c -> c.cancelOrder(trader, o.orderId()));
                }
            }
        }
    }

    private double gauss(double sigma) {
        // Box-Muller from SplittableRandom (not thread-safe, which is fine: one scheduler thread)
        double u = Math.max(1e-12, rnd.nextDouble()), v = rnd.nextDouble();
        return sigma * Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void start() {
        running = true;
        if (!enabled) return;
        try {
            for (MarketCatalog.MarketMeta m : catalog.all()) {
                ensureAccount(MM_BASE + m.id());
                for (int k = 0; k < NOISE_PER_MARKET; k++) ensureAccount(NOISE_BASE + m.id() * 10 + k);
                bots.put(m.id(), new MarketBot(m));
            }
        } catch (Exception e) {
            log.error("bot setup failed; bots disabled", e);
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tv-bots");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (MarketBot b : bots.values()) b.step();
            } catch (RuntimeException e) {
                log.debug("bot tick failed: {}", e.toString());
            }
        }, 500, 250, TimeUnit.MILLISECONDS);
        log.info("bots running on {} markets", bots.size());
    }

    private void ensureAccount(long id) throws Exception {
        if (readModel.account(id) != null) return;
        engine.submit(c -> c.admin(CommandType.CREATE_ACCOUNT, id, 0)).get(5, TimeUnit.SECONDS);
        engine.submit(c -> c.admin(CommandType.DEPOSIT, id, FUNDING)).get(5, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 20;
    }
}
