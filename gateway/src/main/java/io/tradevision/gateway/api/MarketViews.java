package io.tradevision.gateway.api;

import io.tradevision.engine.Prices;
import io.tradevision.gateway.catalog.MarketCatalog;
import io.tradevision.gateway.ingest.PolymarketFeed;
import io.tradevision.gateway.ingest.ReferenceBook;
import io.tradevision.gateway.read.AccountState;
import io.tradevision.gateway.read.MarketState;
import io.tradevision.gateway.read.ReadModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Assembles API-facing views from catalog metadata, read-model state and live reference quotes. */
@Component
public class MarketViews {
    public record MarketSummary(long id, String question, String category, String source, String slug, String closesAt,
                                String image, MarketState.Stats stats, ReferenceBook.Quote reference) {}

    public record PositionDto(long marketId, String question, long yes, long no, long yesLocked, long noLocked,
                              long avgYesMilli, long avgNoMilli, int mark, long marketValue, long cost,
                              long unrealizedPnl, long realizedPnl) {}

    public record Portfolio(long accountId, long available, long reserved, long cash, long positionsValue, long equity,
                            long unrealizedPnl, long realizedPnl, List<PositionDto> positions,
                            List<AccountState.OrderView> openOrders) {}

    private final MarketCatalog catalog;
    private final ReadModel readModel;
    private final PolymarketFeed polymarket;

    public MarketViews(MarketCatalog catalog, ReadModel readModel, PolymarketFeed polymarket) {
        this.catalog = catalog;
        this.readModel = readModel;
        this.polymarket = polymarket;
    }

    public List<MarketSummary> all() {
        List<MarketSummary> out = new ArrayList<>();
        for (MarketCatalog.MarketMeta m : catalog.all()) {
            MarketSummary s = summary(m);
            if (s != null) out.add(s);
        }
        out.sort(Comparator.comparingLong((MarketSummary s) -> -s.stats().notional()).thenComparingLong(MarketSummary::id));
        return out;
    }

    public MarketSummary summary(long id) {
        return catalog.get(id).map(this::summary).orElse(null);
    }

    private MarketSummary summary(MarketCatalog.MarketMeta m) {
        MarketState st = readModel.market(m.id());
        if (st == null) return null;
        return new MarketSummary(m.id(), m.question(), m.category(), m.source().name(), m.slug(), m.closesAt(), m.image(),
                st.stats(), polymarket.reference(m.id()).orElse(null));
    }

    public Portfolio portfolio(AccountState a) {
        AccountState.Snapshot s = a.snapshot();
        long value = 0, unrealized = 0, realized = 0;
        List<PositionDto> positions = new ArrayList<>();
        for (AccountState.PositionView p : s.positions()) {
            realized += p.realizedPnl();
            if (p.yes() == 0 && p.no() == 0 && p.realizedPnl() == 0) continue;
            MarketState ms = readModel.market(p.marketId());
            int mark = ms == null ? Prices.PAYOUT / 2 : ms.mark();
            if (ms != null && ms.status() == io.tradevision.engine.MarketStatus.RESOLVED) mark = 0;
            long mv = p.yes() * mark + p.no() * (long) Prices.complement(mark);
            long cost = p.yesCost() + p.noCost();
            long upnl = (p.yes() == 0 && p.no() == 0) ? 0 : mv - cost;
            value += mv;
            unrealized += upnl;
            String q = catalog.get(p.marketId()).map(MarketCatalog.MarketMeta::question).orElse("#" + p.marketId());
            positions.add(new PositionDto(p.marketId(), q, p.yes(), p.no(), p.yesLocked(), p.noLocked(),
                    p.yes() == 0 ? 0 : p.yesCost() * 1000 / p.yes(), p.no() == 0 ? 0 : p.noCost() * 1000 / p.no(),
                    mark, mv, cost, upnl, p.realizedPnl()));
        }
        long cash = s.available() + s.reserved();
        return new Portfolio(s.accountId(), s.available(), s.reserved(), cash, value, cash + value, unrealized, realized,
                positions, s.openOrders());
    }
}
