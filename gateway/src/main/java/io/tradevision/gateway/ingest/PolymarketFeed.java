package io.tradevision.gateway.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradevision.gateway.TvProperties;
import io.tradevision.gateway.catalog.MarketCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live ingestion of Polymarket's public CLOB market channel.
 *
 * <ul>
 *   <li><b>Discovery</b> via the CLOB REST API (active markets with a live two-sided price).</li>
 *   <li><b>Normalisation</b>: decimal-dollar strings become integer ticks/sizes; both the legacy
 *       ({@code changes[]}) and current ({@code price_changes[]}) delta formats are accepted.</li>
 *   <li><b>Self-healing</b>: application-level PING every 10 s, a watchdog that tears down a connection
 *       silent for 60 s, exponential backoff with jitter on reconnect, and resync-by-resubscribe when a
 *       mirrored book crosses (see {@link ReferenceBook}).</li>
 * </ul>
 * Reference quotes feed the market-making bots, so mirrored paper markets track the real market.
 */
@Component
public class PolymarketFeed implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PolymarketFeed.class);

    public record Stats(boolean enabled, boolean connected, long messages, long events, long applied, long stale,
                        long duplicates, long crossedResyncs, long reconnects, long lastMessageAgeMs, int subscribedTokens) {}

    private final TvProperties.Polymarket cfg;
    private final MarketCatalog catalog;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tv-polymarket");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ReferenceBook> books = new ConcurrentHashMap<>();
    private final Map<Long, String> tokenByMarket = new ConcurrentHashMap<>();
    private final AtomicLong messages = new AtomicLong(), events = new AtomicLong(), applied = new AtomicLong(),
            stale = new AtomicLong(), duplicates = new AtomicLong(), crossed = new AtomicLong(), reconnects = new AtomicLong();

    private volatile WebSocket socket;
    private volatile boolean running;
    private volatile long lastMessageAt;
    private volatile long lastResyncAt;
    private int attempt;

    public PolymarketFeed(TvProperties props, MarketCatalog catalog, ObjectMapper json) {
        this.cfg = props.polymarket();
        this.catalog = catalog;
        this.json = json;
    }

    // ------------------------------------------------------------------ discovery (REST)

    /** Active binary markets with a live price away from the extremes, varied by topic. Empty on any failure. */
    public List<MarketCatalog.MarketMeta> discover(int count, long firstId) {
        List<MarketCatalog.MarketMeta> out = new ArrayList<>();
        if (!cfg.enabled()) return out;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.restUrl() + "/sampling-markets?next_cursor="))
                    .timeout(Duration.ofSeconds(20)).GET().build();
            JsonNode root = json.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
            Set<String> seenTopics = new HashSet<>();
            for (JsonNode m : root.path("data")) {
                if (out.size() >= count) break;
                if (!m.path("accepting_orders").asBoolean() || m.path("closed").asBoolean()) continue;
                JsonNode tokens = m.path("tokens");
                if (tokens.size() != 2 || !"Yes".equalsIgnoreCase(tokens.get(0).path("outcome").asText())) continue;
                double yes = tokens.get(0).path("price").asDouble();
                if (yes < 0.07 || yes > 0.93) continue;
                String question = m.path("question").asText();
                String topic = question.split(" ").length > 4 ? String.join(" ", List.of(question.split(" ")).subList(0, 4)) : question;
                String category = "Other";
                for (JsonNode tag : m.path("tags")) {
                    String t = tag.asText();
                    if (!t.startsWith("Earn") && !t.startsWith("Parent") && !t.equals("All")) { category = t; break; }
                }
                if (!seenTopics.add(topic + "|" + category)) continue;
                out.add(new MarketCatalog.MarketMeta(firstId + out.size(), question, category, MarketCatalog.Source.POLYMARKET,
                        m.path("market_slug").asText(), tokens.get(0).path("token_id").asText(),
                        m.path("end_date_iso").asText(null), m.path("image").asText(null), (int) Math.round(yes * 1000)));
            }
            log.info("polymarket discovery: {} markets", out.size());
        } catch (Exception e) {
            log.warn("polymarket discovery failed ({}); continuing with synthetic markets only", e.toString());
        }
        return out;
    }

    // ------------------------------------------------------------------ streaming (WebSocket)

    @Override
    public void start() {
        running = true;
        if (!cfg.enabled()) return;
        for (MarketCatalog.MarketMeta m : catalog.all()) {
            if (m.source() == MarketCatalog.Source.POLYMARKET && m.yesTokenId() != null) {
                tokenByMarket.put(m.id(), m.yesTokenId());
                books.putIfAbsent(m.yesTokenId(), new ReferenceBook());
            }
        }
        if (tokenByMarket.isEmpty()) return;
        scheduler.scheduleAtFixedRate(this::heartbeat, 10, 10, TimeUnit.SECONDS);
        connect();
    }

    /** Called by the bootstrapper after new Polymarket markets were added to the catalog. */
    public void refreshSubscriptions() {
        if (!running || !cfg.enabled()) return;
        boolean wasEmpty = tokenByMarket.isEmpty();
        for (MarketCatalog.MarketMeta m : catalog.all()) {
            if (m.source() == MarketCatalog.Source.POLYMARKET && m.yesTokenId() != null) {
                tokenByMarket.put(m.id(), m.yesTokenId());
                books.putIfAbsent(m.yesTokenId(), new ReferenceBook());
            }
        }
        if (tokenByMarket.isEmpty()) return;
        if (wasEmpty) {
            scheduler.scheduleAtFixedRate(this::heartbeat, 10, 10, TimeUnit.SECONDS);
            connect();
        } else {
            resync("subscription change");
        }
    }

    private void connect() {
        if (!running) return;
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(cfg.wsUrl()), new Listener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.warn("polymarket connect failed: {}", err.toString());
                        scheduleReconnect();
                        return;
                    }
                    socket = ws;
                    lastMessageAt = System.currentTimeMillis();
                    Map<String, Object> sub = new LinkedHashMap<>();
                    sub.put("assets_ids", new ArrayList<>(tokenByMarket.values()));
                    sub.put("type", "market");
                    try {
                        ws.sendText(json.writeValueAsString(sub), true);
                        log.info("polymarket connected, subscribed to {} tokens", tokenByMarket.size());
                    } catch (Exception e) {
                        ws.abort();
                    }
                });
    }

    private void scheduleReconnect() {
        if (!running) return;
        socket = null;
        reconnects.incrementAndGet();
        long base = Math.min(30_000, 1_000L << Math.min(attempt++, 5));
        long delay = (long) (base * (0.5 + ThreadLocalRandom.current().nextDouble()));
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private void heartbeat() {
        WebSocket ws = socket;
        if (ws == null) return;
        if (System.currentTimeMillis() - lastMessageAt > 60_000) {
            log.warn("polymarket feed silent for 60s, reconnecting");
            ws.abort(); // onError/onClose will not fire after abort; schedule explicitly
            scheduleReconnect();
            return;
        }
        ws.sendText("PING", true);
    }

    private void resync(String why) {
        long now = System.currentTimeMillis();
        if (now - lastResyncAt < 30_000) return; // never thrash the venue
        lastResyncAt = now;
        crossed.incrementAndGet();
        log.info("polymarket resync: {}", why);
        WebSocket ws = socket;
        if (ws != null) {
            ws.abort();
            scheduleReconnect();
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                lastMessageAt = System.currentTimeMillis();
                attempt = 0;
                messages.incrementAndGet();
                try {
                    handle(text);
                } catch (Exception e) {
                    log.debug("bad polymarket message: {}", e.toString());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            log.info("polymarket closed {} {}", status, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("polymarket error: {}", error.toString());
            scheduleReconnect();
        }
    }

    /** Parse and apply one frame. Package-private for fixture tests. */
    void handle(String text) throws Exception {
        String t = text.trim();
        if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) return; // PONG etc.
        JsonNode root = json.readTree(t);
        if (root.isArray()) for (JsonNode n : root) event(n);
        else event(root);
    }

    private void event(JsonNode n) {
        events.incrementAndGet();
        String type = n.path("event_type").asText();
        long ts = n.path("timestamp").asLong(System.currentTimeMillis());
        switch (type) {
            case "book" -> {
                ReferenceBook book = books.get(n.path("asset_id").asText());
                if (book == null) return;
                JsonNode b = n.has("bids") ? n.get("bids") : n.path("buys");
                JsonNode a = n.has("asks") ? n.get("asks") : n.path("sells");
                int[] bp = new int[b.size()], ap = new int[a.size()];
                long[] bs = new long[b.size()], as = new long[a.size()];
                for (int i = 0; i < b.size(); i++) { bp[i] = ReferenceBook.ticks(b.get(i).path("price").asText()); bs[i] = ReferenceBook.size(b.get(i).path("size").asText()); }
                for (int i = 0; i < a.size(); i++) { ap[i] = ReferenceBook.ticks(a.get(i).path("price").asText()); as[i] = ReferenceBook.size(a.get(i).path("size").asText()); }
                book.snapshot(ts, bp, bs, ap, as);
                applied.incrementAndGet();
            }
            case "price_change" -> {
                if (n.has("price_changes")) {
                    for (JsonNode pc : n.get("price_changes")) {
                        delta(pc.path("asset_id").asText(), ts, pc.path("hash").asText(null), pc);
                    }
                } else {
                    String asset = n.path("asset_id").asText();
                    for (JsonNode c : n.path("changes")) delta(asset, ts, n.path("hash").asText(null) + c.toString(), c);
                }
            }
            case "last_trade_price" -> {
                ReferenceBook book = books.get(n.path("asset_id").asText());
                if (book != null) {
                    book.lastTrade(ReferenceBook.ticks(n.path("price").asText()));
                    applied.incrementAndGet();
                }
            }
            default -> { } // tick_size_change, best_bid_ask, etc.
        }
    }

    private void delta(String asset, long ts, String hash, JsonNode c) {
        ReferenceBook book = books.get(asset);
        if (book == null) return;
        boolean bid = "BUY".equalsIgnoreCase(c.path("side").asText());
        switch (book.delta(ts, hash, bid, ReferenceBook.ticks(c.path("price").asText()), ReferenceBook.size(c.path("size").asText()))) {
            case OK -> applied.incrementAndGet();
            case STALE -> stale.incrementAndGet();
            case DUPLICATE -> duplicates.incrementAndGet();
            case CROSSED -> {
                applied.incrementAndGet();
                resync("crossed book on " + asset.substring(0, Math.min(10, asset.length())));
            }
        }
    }

    // ------------------------------------------------------------------ queries

    /** Latest reference quote for one of our market ids, if it mirrors Polymarket and data is under 2 minutes old. */
    public Optional<ReferenceBook.Quote> reference(long marketId) {
        String token = tokenByMarket.get(marketId);
        if (token == null) return Optional.empty();
        ReferenceBook book = books.get(token);
        if (book == null || !book.hasSnapshot()) return Optional.empty();
        ReferenceBook.Quote q = book.quote();
        return System.currentTimeMillis() - q.updatedAtMillis() < 120_000 && q.mid() > 0 ? Optional.of(q) : Optional.empty();
    }

    ReferenceBook bookFor(String token) {
        return books.computeIfAbsent(token, k -> new ReferenceBook());
    }

    public Stats stats() {
        return new Stats(cfg.enabled(), socket != null, messages.get(), events.get(), applied.get(), stale.get(),
                duplicates.get(), crossed.get(), reconnects.get(),
                lastMessageAt == 0 ? -1 : System.currentTimeMillis() - lastMessageAt, tokenByMarket.size());
    }

    @Override
    public void stop() {
        running = false;
        WebSocket ws = socket;
        if (ws != null) ws.abort();
        scheduler.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 10;
    }
}
