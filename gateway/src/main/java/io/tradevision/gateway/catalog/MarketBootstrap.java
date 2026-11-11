package io.tradevision.gateway.catalog;

import io.tradevision.engine.CommandType;
import io.tradevision.gateway.engine.EngineHost;
import io.tradevision.gateway.ingest.PolymarketFeed;
import io.tradevision.gateway.read.ReadModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * First-run setup: a fixed set of synthetic markets plus live Polymarket mirrors (if reachable).
 * Idempotent across restarts: markets already in the engine are skipped.
 */
@Component
public class MarketBootstrap implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(MarketBootstrap.class);

    private static final List<Object[]> SYNTHETIC = List.of(
            new Object[] {"Will BTC trade above $150k before Jan 1, 2027?", "Crypto", 380},
            new Object[] {"Will the ETH/BTC ratio exceed 0.05 by end of Q1 2027?", "Crypto", 220},
            new Object[] {"Will the Fed cut rates at the next FOMC meeting?", "Economics", 610},
            new Object[] {"Will the next US CPI print come in below 2.5% YoY?", "Economics", 450},
            new Object[] {"Will 3+ goals be scored in the next Champions League final?", "Sports", 330},
            new Object[] {"Will next month set a new global monthly temperature record?", "Climate", 540},
            new Object[] {"Will a frontier AI lab ship a new flagship model this quarter?", "Tech", 720},
            new Object[] {"Will an orbital launch attempt happen this week?", "Science", 810});

    private final MarketCatalog catalog;
    private final EngineHost engine;
    private final ReadModel readModel;
    private final PolymarketFeed polymarket;
    private final int polymarketCount;
    private volatile boolean running;

    public MarketBootstrap(MarketCatalog catalog, EngineHost engine, ReadModel readModel, PolymarketFeed polymarket,
                           io.tradevision.gateway.TvProperties props) {
        this.catalog = catalog;
        this.engine = engine;
        this.readModel = readModel;
        this.polymarket = polymarket;
        this.polymarketCount = props.polymarket().markets();
    }

    @Override
    public void start() {
        running = true;
        if (catalog.all().isEmpty()) {
            long id = 1;
            for (Object[] s : SYNTHETIC) {
                catalog.add(new MarketCatalog.MarketMeta(id++, (String) s[0], (String) s[1], MarketCatalog.Source.SYNTHETIC,
                        null, null, null, null, (Integer) s[2]));
            }
        }
        boolean hasPolymarket = catalog.all().stream().anyMatch(m -> m.source() == MarketCatalog.Source.POLYMARKET);
        if (!hasPolymarket) {
            for (MarketCatalog.MarketMeta m : polymarket.discover(polymarketCount, catalog.nextId())) catalog.add(m);
        }
        int created = 0;
        for (MarketCatalog.MarketMeta m : catalog.all()) {
            if (readModel.market(m.id()) != null) continue;
            try {
                engine.submit(c -> c.admin(CommandType.CREATE_MARKET, m.id(), 0)).get(5, TimeUnit.SECONDS);
                created++;
            } catch (Exception e) {
                throw new IllegalStateException("could not create market " + m.id(), e);
            }
        }
        polymarket.refreshSubscriptions();
        log.info("markets ready: {} total, {} newly created", catalog.all().size(), created);
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 5;
    }
}
