package io.tradevision.gateway.api;

import io.tradevision.engine.CommandType;
import io.tradevision.engine.Outcome;
import io.tradevision.engine.runtime.EngineRunner;
import io.tradevision.engine.runtime.LatencyHistogram;
import io.tradevision.gateway.TvProperties;
import io.tradevision.gateway.engine.EngineHost;
import io.tradevision.gateway.engine.Event;
import io.tradevision.gateway.analytics.ClickHouseAnalytics;
import io.tradevision.gateway.ingest.PolymarketFeed;
import io.tradevision.gateway.projection.PostgresProjector;
import io.tradevision.gateway.projection.PostgresQueries;
import io.tradevision.gateway.read.AccountState;
import io.tradevision.gateway.read.MarketState;
import io.tradevision.gateway.read.ReadModel;
import io.tradevision.gateway.ws.MarketDataHub;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final EngineHost engine;
    private final ReadModel readModel;
    private final MarketViews views;
    private final OrderService orders;
    private final AuthService auth;
    private final PolymarketFeed polymarket;
    private final MarketDataHub hub;
    private final TvProperties props;
    private final RateMeter rate;
    private final ObjectProvider<PostgresProjector> pgProjector;
    private final ObjectProvider<PostgresQueries> pgQueries;
    private final ObjectProvider<ClickHouseAnalytics> clickhouse;

    public ApiController(EngineHost engine, ReadModel readModel, MarketViews views, OrderService orders, AuthService auth,
                         PolymarketFeed polymarket, MarketDataHub hub, TvProperties props, RateMeter rate,
                         ObjectProvider<PostgresProjector> pgProjector, ObjectProvider<PostgresQueries> pgQueries,
                         ObjectProvider<ClickHouseAnalytics> clickhouse) {
        this.pgProjector = pgProjector;
        this.pgQueries = pgQueries;
        this.clickhouse = clickhouse;
        this.engine = engine;
        this.readModel = readModel;
        this.views = views;
        this.orders = orders;
        this.auth = auth;
        this.polymarket = polymarket;
        this.hub = hub;
        this.props = props;
        this.rate = rate;
    }

    private long account(String authorization) {
        return auth.verify(authorization).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing or invalid token"));
    }

    // ------------------------------------------------------------------ session

    public record Session(String token, long accountId) {}

    /** Create a paper-trading account funded with the configured starting balance. */
    @PostMapping("/session")
    public CompletableFuture<Session> session() {
        long id = engine.allocateAccountId();
        long amount = props.startingBalanceDollars() * 1000;
        return engine.submit(c -> c.admin(CommandType.CREATE_ACCOUNT, id, 0))
                .thenCompose(e -> engine.submit(c -> c.admin(CommandType.DEPOSIT, id, amount)))
                .thenApply(e -> new Session(auth.issue(id), id));
    }

    // ------------------------------------------------------------------ markets

    @GetMapping("/markets")
    public List<MarketViews.MarketSummary> markets() {
        return views.all();
    }

    public record MarketDetail(MarketViews.MarketSummary market, MarketState.Depth depth, List<MarketState.TapeEntry> trades) {}

    @GetMapping("/markets/{id}")
    public MarketDetail market(@PathVariable long id, @RequestParam(defaultValue = "25") int levels) {
        MarketViews.MarketSummary s = views.summary(id);
        MarketState st = readModel.market(id);
        if (s == null || st == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no market " + id);
        return new MarketDetail(s, st.depth(Math.min(levels, 100)), st.tape(60));
    }

    @GetMapping("/markets/{id}/candles")
    public Object candles(@PathVariable long id, @RequestParam(defaultValue = "5s") String tf) {
        MarketState st = readModel.market(id);
        if (st == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no market " + id);
        return st.candles(tf);
    }

    // ------------------------------------------------------------------ account

    @GetMapping("/me")
    public MarketViews.Portfolio me(@RequestHeader(value = "Authorization", required = false) String authz) {
        AccountState a = readModel.account(account(authz));
        if (a == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found");
        return views.portfolio(a);
    }

    @GetMapping("/me/orders")
    public List<AccountState.OrderView> myOrders(@RequestHeader(value = "Authorization", required = false) String authz,
                                                 @RequestParam(defaultValue = "open") String status) {
        AccountState a = readModel.account(account(authz));
        if (a == null) return List.of();
        return "open".equals(status) ? a.openOrders() : a.history(200);
    }

    @GetMapping("/me/fills")
    public List<AccountState.Fill> myFills(@RequestHeader(value = "Authorization", required = false) String authz) {
        AccountState a = readModel.account(account(authz));
        return a == null ? List.of() : a.fills(200);
    }

    public record Page<T>(String source, List<T> items, Long next) {}

    /**
     * Complete fill history, newest first, keyset-paginated: pass the returned {@code next} as {@code before}.
     * Served from Postgres when the projection is enabled; otherwise only the recent in-memory window exists.
     */
    @GetMapping("/me/history/fills")
    public Page<?> fillHistory(@RequestHeader(value = "Authorization", required = false) String authz,
                               @RequestParam(required = false) Long before,
                               @RequestParam(defaultValue = "50") int limit) {
        long acct = account(authz);
        long b = before == null ? Long.MAX_VALUE : before;
        int n = Math.max(1, Math.min(limit, 500));
        PostgresQueries pg = pgQueries.getIfAvailable();
        if (pg != null) {
            try {
                var rows = pg.fills(acct, b, n);
                return new Page<>("postgres", rows, rows.size() < n ? null : rows.get(rows.size() - 1).tradeId());
            } catch (RuntimeException e) {
                // database unavailable: fall through to the in-memory window
            }
        }
        AccountState a = readModel.account(acct);
        var rows = a == null ? List.<AccountState.Fill>of()
                : a.fills(300).stream().filter(f -> f.tradeId() < b).limit(n).toList();
        return new Page<>("memory", rows, rows.size() < n ? null : rows.get(rows.size() - 1).tradeId());
    }

    @GetMapping("/me/history/orders")
    public Page<?> orderHistory(@RequestHeader(value = "Authorization", required = false) String authz,
                                @RequestParam(required = false) Long before,
                                @RequestParam(defaultValue = "50") int limit) {
        long acct = account(authz);
        long b = before == null ? Long.MAX_VALUE : before;
        int n = Math.max(1, Math.min(limit, 500));
        PostgresQueries pg = pgQueries.getIfAvailable();
        if (pg != null) {
            try {
                var rows = pg.orders(acct, b, n);
                return new Page<>("postgres", rows, rows.size() < n ? null : rows.get(rows.size() - 1).orderId());
            } catch (RuntimeException e) {
                // database unavailable: fall through to the in-memory window
            }
        }
        AccountState a = readModel.account(acct);
        var rows = a == null ? List.<AccountState.OrderView>of()
                : a.history(300).stream().filter(o -> o.orderId() < b).limit(n).toList();
        return new Page<>("memory", rows, rows.size() < n ? null : rows.get(rows.size() - 1).orderId());
    }

    // ------------------------------------------------------------------ trading

    @PostMapping("/orders")
    public CompletableFuture<OrderService.OrderResult> place(@RequestHeader(value = "Authorization", required = false) String authz,
                                                             @RequestBody OrderService.PlaceOrder req) {
        return orders.place(account(authz), req);
    }

    @DeleteMapping("/orders/{orderId}")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> cancel(
            @RequestHeader(value = "Authorization", required = false) String authz, @PathVariable long orderId) {
        return orders.cancel(account(authz), orderId).thenApply(rejection -> rejection == null
                ? ResponseEntity.ok(Map.of("orderId", orderId, "status", "CANCELED"))
                : ResponseEntity.status(rejection.equals("UNKNOWN_ORDER") ? 404 : 403)
                        .body(Map.of("orderId", orderId, "error", rejection)));
    }

    /** Cancel by the client-assigned id, safe even while the original order is still in flight. */
    @DeleteMapping("/orders/client/{clientOrderId}")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> cancelByClientId(
            @RequestHeader(value = "Authorization", required = false) String authz, @PathVariable long clientOrderId) {
        return orders.cancelByClientId(account(authz), clientOrderId).thenApply(rejection -> rejection == null
                ? ResponseEntity.ok(Map.of("clientOrderId", clientOrderId, "status", "CANCELED"))
                : ResponseEntity.status(rejection.equals("UNKNOWN_ORDER") ? 404 : 403)
                        .body(Map.of("clientOrderId", clientOrderId, "error", rejection)));
    }

    /** Cancel all of my open orders, optionally in one market. */
    @DeleteMapping("/orders")
    public CompletableFuture<Map<String, Object>> cancelAll(@RequestHeader(value = "Authorization", required = false) String authz,
                                                            @RequestParam(required = false) Long marketId) {
        long acct = account(authz);
        AccountState a = readModel.account(acct);
        List<AccountState.OrderView> open = a == null ? List.of() : marketId == null ? a.openOrders() : a.openOrders(marketId);
        List<CompletableFuture<String>> fs = new ArrayList<>();
        for (AccountState.OrderView o : open) fs.add(orders.cancel(acct, o.orderId()));
        return CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new))
                .thenApply(v -> Map.of("requested", fs.size()));
    }

    // ------------------------------------------------------------------ admin

    @PostMapping("/admin/markets/{id}/{action}")
    public CompletableFuture<Object> admin(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                           @PathVariable long id, @PathVariable String action,
                                           @RequestParam(required = false) String winner) {
        if (token == null || !token.equals(props.adminToken())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
        CompletableFuture<List<Event>> f = switch (action) {
            case "halt" -> engine.submit(c -> c.admin(CommandType.HALT_MARKET, id, 0));
            case "reopen" -> engine.submit(c -> c.admin(CommandType.REOPEN_MARKET, id, 0));
            case "resolve" -> {
                Outcome w = Outcome.valueOf(String.valueOf(winner).toUpperCase());
                yield engine.submit(c -> c.resolve(id, w));
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown action " + action);
        };
        return f.thenApply(events -> events.stream().filter(e -> e instanceof Event.CommandRejected).findFirst()
                .<Object>map(e -> Map.of("error", ((Event.CommandRejected) e).reason().name()))
                .orElse(Map.of("ok", true, "events", events.size())));
    }

    // ------------------------------------------------------------------ observability

    @GetMapping("/system")
    public Map<String, Object> system() {
        EngineRunner r = engine.runner();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commandsProcessed", r.processed());
        m.put("commandsPerSecond", rate.perSecond());
        m.put("durableSequence", r.durableSequence());
        m.put("inboundQueueDepth", r.queueDepth());
        m.put("outboundQueueDepth", engine.outboundDepth());
        m.put("matchNanos", hist(r.applyTime(), 1));
        m.put("ingressToAppliedMicros", hist(r.ingressToApplied(), 1000));
        m.put("ingressToDurableMicros", hist(r.ingressToDurable(), 1000));
        m.put("journalFlushes", r.journalFlushes());
        m.put("journalBytes", r.journalBytes());
        m.put("recovery", Map.of("sequence", engine.recoveredSequence(), "replayedCommands", engine.replayedOnStart()));
        m.put("websocketClients", hub.clientCount());
        m.put("polymarket", polymarket.stats());
        var snap = engine.snapshots();
        m.put("snapshots", snap == null ? Map.of("mode", "INLINE")
                : Map.of("mode", "FOLLOWER", "followerSequence", snap.position(), "lastSnapshotSequence",
                        snap.lastSnapshotSequence(), "lastSnapshotMillis", snap.lastSnapshotMillis(),
                        "taken", snap.snapshotsTaken()));
        PostgresProjector pg = pgProjector.getIfAvailable();
        if (pg != null) {
            var st = pg.status();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("status", st);
            p.put("lag", Math.max(0, r.durableSequence() - st.offset()));
            m.put("postgres", p);
        }
        ClickHouseAnalytics ch = clickhouse.getIfAvailable();
        if (ch != null) m.put("clickhouse", ch.status());
        return m;
    }

    private static Map<String, Long> hist(LatencyHistogram h, long div) {
        return Map.of("p50", h.percentile(50) / div, "p99", h.percentile(99) / div, "p999", h.percentile(99.9) / div,
                "max", h.max() / div, "count", h.count());
    }
}
