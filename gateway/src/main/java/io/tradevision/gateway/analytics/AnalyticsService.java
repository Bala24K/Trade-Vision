package io.tradevision.gateway.analytics;

import io.tradevision.gateway.api.MarketViews;
import io.tradevision.gateway.catalog.MarketCatalog;
import io.tradevision.gateway.ingest.PolymarketFeed;
import io.tradevision.gateway.ingest.ReferenceBook;
import io.tradevision.gateway.projection.PostgresQueries;
import io.tradevision.gateway.read.AccountState;
import io.tradevision.gateway.read.MarketState;
import io.tradevision.gateway.read.ReadModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Market analytics and the leaderboard. Each answer says where it came from ({@code source}):
 * ClickHouse/Postgres when configured and reachable, otherwise the in-memory stores. A database outage degrades
 * the history depth, never the availability of the page.
 */
@Service
public class AnalyticsService {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final long BOT_ACCOUNT_FLOOR = 1_000_000;

    public record BucketDto(long t, Integer open, Integer high, Integer low, Integer close, long volume, long buyVolume,
                            long sellVolume, long trades, Double vwap, Double spread, Double imbalance, Double refMid) {}

    public record Live(Integer bestBid, Integer bestAsk, Integer spread, long bidDepth5, long askDepth5, Double imbalance,
                       Integer refMid, Integer mark) {}

    public record MarketAnalytics(long marketId, String question, String source, int windowMinutes, int bucketSeconds,
                                  Buckets.Metrics metrics, Live live, List<BucketDto> buckets) {}

    public record ScannerRow(long marketId, String question, String category, String marketSource, Buckets.Metrics metrics,
                             Live live) {}

    public record LeaderRow(int rank, long accountId, boolean bot, long realizedPnl, long unrealizedPnl, long totalPnl,
                            int openPositions) {}

    public record Leaderboard(String source, List<LeaderRow> rows) {}

    private final MinuteBars bars;
    private final ObjectProvider<ClickHouseAnalytics> clickhouse;
    private final ObjectProvider<PostgresQueries> postgres;
    private final ReadModel readModel;
    private final MarketViews views;
    private final MarketCatalog catalog;
    private final PolymarketFeed polymarket;

    public AnalyticsService(MinuteBars bars, ObjectProvider<ClickHouseAnalytics> clickhouse,
                            ObjectProvider<PostgresQueries> postgres, ReadModel readModel, MarketViews views,
                            MarketCatalog catalog, PolymarketFeed polymarket) {
        this.bars = bars;
        this.clickhouse = clickhouse;
        this.postgres = postgres;
        this.readModel = readModel;
        this.views = views;
        this.catalog = catalog;
        this.polymarket = polymarket;
    }

    /** Bucket width that keeps any window at a chartable ~60-300 points. */
    static int bucketSecondsFor(int windowMinutes) {
        if (windowMinutes <= 180) return 60;
        if (windowMinutes <= 1_440) return 300;
        if (windowMinutes <= 10_080) return 1_800;
        return 3_600;
    }

    public MarketAnalytics market(long marketId, int windowMinutes) {
        MarketState st = readModel.market(marketId);
        if (st == null) return null;
        int window = Math.max(5, Math.min(windowMinutes, 30 * 1_440));
        int bucket = bucketSecondsFor(window);
        long from = System.currentTimeMillis() / 1000 - window * 60L;
        from -= Math.floorMod(from, bucket);

        List<Bucket> series = null;
        String source = "memory";
        ClickHouseAnalytics ch = clickhouse.getIfAvailable();
        if (ch != null && ch.status().up()) {
            try {
                series = ch.buckets(marketId, from, bucket);
                source = "clickhouse";
            } catch (Exception e) {
                log.debug("clickhouse query failed, using memory", e);
                source = "memory (clickhouse unavailable)";
            }
        }
        if (series == null) series = bars.buckets(marketId, from, bucket);
        return new MarketAnalytics(marketId, question(marketId), source, window, bucket, Buckets.metrics(series, bucket),
                live(st), series.stream().map(AnalyticsService::dto).toList());
    }

    /** All markets, last hour, from memory: cheap enough to poll every few seconds. */
    public List<ScannerRow> scanner() {
        long from = System.currentTimeMillis() / 1000 - 3_600;
        List<ScannerRow> out = new ArrayList<>();
        for (MarketCatalog.MarketMeta m : catalog.all()) {
            MarketState st = readModel.market(m.id());
            if (st == null) continue;
            out.add(new ScannerRow(m.id(), m.question(), m.category(), m.source().name(),
                    Buckets.metrics(bars.buckets(m.id(), from - Math.floorMod(from, 60), 60), 60), live(st)));
        }
        out.sort(Comparator.comparingLong((ScannerRow r) -> -r.metrics().volume()));
        return out;
    }

    public Leaderboard leaderboard(int limit, boolean includeBots) {
        int n = Math.max(1, Math.min(limit, 100));
        PostgresQueries pg = postgres.getIfAvailable();
        if (pg != null) {
            try {
                List<PostgresQueries.LeaderRow> rows = pg.leaderboard(n, includeBots);
                List<LeaderRow> out = new ArrayList<>(rows.size());
                for (int i = 0; i < rows.size(); i++) {
                    var r = rows.get(i);
                    out.add(new LeaderRow(i + 1, r.accountId(), r.bot(), r.realizedPnl(), r.unrealizedPnl(), r.totalPnl(),
                            r.openPositions()));
                }
                return new Leaderboard("postgres", out);
            } catch (Exception e) {
                log.debug("postgres leaderboard failed, using memory", e);
            }
        }
        // In-memory fallback: same formula, computed from the read model's portfolio view.
        List<LeaderRow> all = new ArrayList<>();
        for (AccountState a : readModel.accounts()) {
            boolean bot = a.accountId >= BOT_ACCOUNT_FLOOR;
            if (bot && !includeBots) continue;
            MarketViews.Portfolio p = views.portfolio(a);
            if (p.positions().isEmpty()) continue;
            int open = (int) p.positions().stream().filter(x -> x.yes() != 0 || x.no() != 0).count();
            all.add(new LeaderRow(0, a.accountId, bot, p.realizedPnl(), p.unrealizedPnl(),
                    p.realizedPnl() + p.unrealizedPnl(), open));
        }
        all.sort(Comparator.comparingLong(LeaderRow::totalPnl).reversed());
        List<LeaderRow> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, all.size()); i++) {
            LeaderRow r = all.get(i);
            out.add(new LeaderRow(i + 1, r.accountId(), r.bot(), r.realizedPnl(), r.unrealizedPnl(), r.totalPnl(), r.openPositions()));
        }
        return new Leaderboard("memory", out);
    }

    private Live live(MarketState st) {
        MarketState.Depth d = st.depth(5);
        long bd = 0, ad = 0;
        for (MarketState.Level l : d.bids()) bd += l.quantity();
        for (MarketState.Level l : d.asks()) ad += l.quantity();
        Integer bb = d.bids().isEmpty() ? null : d.bids().get(0).price();
        Integer ba = d.asks().isEmpty() ? null : d.asks().get(0).price();
        ReferenceBook.Quote q = polymarket.reference(st.marketId).orElse(null);
        Integer ref = q != null && q.bestBid() > 0 && q.bestAsk() > 0 ? (q.bestBid() + q.bestAsk()) / 2 : null;
        return new Live(bb, ba, bb != null && ba != null ? ba - bb : null, bd, ad,
                bd + ad == 0 ? null : (double) (bd - ad) / (bd + ad), ref, st.mark());
    }

    private String question(long id) {
        return catalog.get(id).map(MarketCatalog.MarketMeta::question).orElse("#" + id);
    }

    private static BucketDto dto(Bucket b) {
        boolean t = b.traded();
        return new BucketDto(b.t(), t ? b.open() : null, t ? b.high() : null, t ? b.low() : null, t ? b.close() : null,
                b.volume(), b.buyVolume(), b.sellVolume(), b.trades(), b.volume() == 0 ? null : (double) b.notional() / b.volume(),
                nan(b.spread()), nan(b.imbalance()), nan(b.refMid()));
    }

    private static Double nan(double v) {
        return Double.isNaN(v) ? null : v;
    }
}
