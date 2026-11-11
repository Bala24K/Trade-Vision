package io.tradevision.gateway.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradevision.engine.Side;
import io.tradevision.gateway.api.AuthService;
import io.tradevision.gateway.api.MarketViews;
import io.tradevision.gateway.engine.EngineHost;
import io.tradevision.gateway.engine.Event;
import io.tradevision.gateway.read.MarketState;
import io.tradevision.gateway.read.ReadModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Real-time fan-out to browser clients.
 *
 * <h2>Conflation</h2>
 * A busy book changes thousands of times a second; a human (and a browser's 60 Hz paint loop) cannot use more
 * than a few updates per second. Book changes are only <i>marked dirty</i> on the publisher thread; every 50 ms
 * the flush thread reads the current quantity of each dirty level and sends one delta per market. Values are
 * absolute, so dropping intermediate states loses nothing a client could display.
 *
 * <h2>Ordering & gap detection</h2>
 * Snapshots and deltas carry the market's book sequence. Each delta also carries {@code prevSeq}, the last
 * sequence this client was sent; a client that sees a mismatch re-subscribes for a fresh snapshot.
 *
 * <h2>Slow consumers</h2>
 * Every session is wrapped in a bounded {@link ConcurrentWebSocketSessionDecorator}: a client that cannot keep up
 * (send blocked > 2 s or > 1 MB buffered) is disconnected instead of slowing everyone else down.
 */
@Component
public class MarketDataHub extends TextWebSocketHandler implements EngineHost.Sink {
    private static final Logger log = LoggerFactory.getLogger(MarketDataHub.class);

    private static final class Client {
        final WebSocketSession session;
        final Set<Long> books = ConcurrentHashMap.newKeySet();
        final Map<Long, Long> lastSeq = new ConcurrentHashMap<>();
        volatile long accountId;

        Client(WebSocketSession s) {
            this.session = new ConcurrentWebSocketSessionDecorator(s, 2_000, 1 << 20);
        }
    }

    private static final class Dirty {
        final Set<Integer> bids = new HashSet<>();
        final Set<Integer> asks = new HashSet<>();
        final List<MarketState.TapeEntry> trades = new ArrayList<>();
    }

    private final ObjectMapper json;
    private final ReadModel readModel;
    private final MarketViews views;
    private final AuthService auth;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<Long, Dirty> dirty = new ConcurrentHashMap<>();
    private final Set<Long> tickerDirty = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tv-ws-flush");
        t.setDaemon(true);
        return t;
    });
    private int flushes;

    public MarketDataHub(ObjectMapper json, ReadModel readModel, MarketViews views, AuthService auth, EngineHost engine) {
        this.json = json;
        this.readModel = readModel;
        this.views = views;
        this.auth = auth;
        engine.addSink(this);
        flusher.scheduleAtFixedRate(this::flush, 50, 50, TimeUnit.MILLISECONDS);
    }

    public int clientCount() {
        return clients.size();
    }

    // ------------------------------------------------------------------ publisher thread

    @Override
    public void onEvents(long sequence, List<Event> events) {
        Set<Long> accountsTouched = null;
        for (Event ev : events) {
            switch (ev) {
                case Event.BookLevel b -> {
                    Dirty d = dirty.computeIfAbsent(b.marketId(), k -> new Dirty());
                    synchronized (d) {
                        (b.side() == Side.BID ? d.bids : d.asks).add(b.price());
                    }
                    tickerDirty.add(b.marketId());
                }
                case Event.Trade t -> {
                    Dirty d = dirty.computeIfAbsent(t.marketId(), k -> new Dirty());
                    synchronized (d) {
                        d.trades.add(new MarketState.TapeEntry(t.tradeId(), t.price(), t.quantity(), t.aggressor(), t.ts()));
                    }
                    tickerDirty.add(t.marketId());
                    if (accountsTouched == null) accountsTouched = new HashSet<>();
                    accountsTouched.add(t.makerAccountId());
                    accountsTouched.add(t.takerAccountId());
                }
                case Event.OrderAccepted a -> (accountsTouched = touched(accountsTouched)).add(a.accountId());
                case Event.OrderClosed c -> (accountsTouched = touched(accountsTouched)).add(c.accountId());
                case Event.OrderRejected r -> (accountsTouched = touched(accountsTouched)).add(r.accountId());
                case Event.Settlement s -> (accountsTouched = touched(accountsTouched)).add(s.accountId());
                case Event.Balance b -> (accountsTouched = touched(accountsTouched)).add(b.accountId());
                case Event.MarketStatusChanged m -> tickerDirty.add(m.marketId());
                default -> { }
            }
        }
        if (accountsTouched != null) {
            for (Client c : clients.values()) {
                if (c.accountId != 0 && accountsTouched.contains(c.accountId)) {
                    send(c, Map.of("type", "account", "ts", System.currentTimeMillis()));
                }
            }
        }
    }

    private static Set<Long> touched(Set<Long> s) {
        return s == null ? new HashSet<>() : s;
    }

    // ------------------------------------------------------------------ flush thread

    private void flush() {
        try {
            for (Map.Entry<Long, Dirty> e : dirty.entrySet()) {
                long marketId = e.getKey();
                Dirty d = e.getValue();
                List<Integer> bids, asks;
                List<MarketState.TapeEntry> trades;
                synchronized (d) {
                    if (d.bids.isEmpty() && d.asks.isEmpty() && d.trades.isEmpty()) continue;
                    bids = new ArrayList<>(d.bids);
                    asks = new ArrayList<>(d.asks);
                    trades = new ArrayList<>(d.trades);
                    d.bids.clear();
                    d.asks.clear();
                    d.trades.clear();
                }
                MarketState ms = readModel.market(marketId);
                if (ms == null) continue;
                MarketState.Depth delta = (bids.isEmpty() && asks.isEmpty()) ? null : ms.levels(bids, asks);
                for (Client c : clients.values()) {
                    if (!c.books.contains(marketId)) continue;
                    if (!trades.isEmpty()) send(c, Map.of("type", "trades", "marketId", marketId, "trades", trades));
                    if (delta != null) sendDelta(c, delta);
                }
            }
            if (++flushes % 5 == 0 && !tickerDirty.isEmpty()) {
                List<Long> ids = new ArrayList<>(tickerDirty);
                tickerDirty.removeAll(ids);
                List<MarketViews.MarketSummary> items = new ArrayList<>(ids.size());
                for (long id : ids) {
                    MarketViews.MarketSummary s = views.summary(id);
                    if (s != null) items.add(s);
                }
                Map<String, Object> msg = Map.of("type", "tickers", "items", items);
                for (Client c : clients.values()) send(c, msg);
            }
        } catch (RuntimeException ex) {
            log.warn("ws flush failed", ex);
        }
    }

    private void sendDelta(Client c, MarketState.Depth delta) {
        synchronized (c) {
            long prev = c.lastSeq.getOrDefault(delta.marketId(), 0L);
            if (delta.seq() <= prev) return; // the client's snapshot is already newer
            c.lastSeq.put(delta.marketId(), delta.seq());
            send(c, book(delta, false, prev));
        }
    }

    private Map<String, Object> book(MarketState.Depth d, boolean snapshot, long prevSeq) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "book");
        m.put("marketId", d.marketId());
        m.put("snapshot", snapshot);
        m.put("seq", d.seq());
        m.put("prevSeq", prevSeq);
        m.put("bids", d.bids().stream().map(l -> new long[] {l.price(), l.quantity()}).toList());
        m.put("asks", d.asks().stream().map(l -> new long[] {l.price(), l.quantity()}).toList());
        return m;
    }

    // ------------------------------------------------------------------ client protocol

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Client c = new Client(session);
        clients.put(session.getId(), c);
        send(c, Map.of("type", "hello", "serverTime", System.currentTimeMillis()));
        send(c, Map.of("type", "tickers", "items", views.all()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Client c = clients.get(session.getId());
        if (c == null) return;
        JsonNode m = json.readTree(message.getPayload());
        switch (m.path("op").asText()) {
            case "auth" -> {
                c.accountId = auth.verify(m.path("token").asText(null)).orElse(0);
                send(c, Map.of("type", "auth", "ok", c.accountId != 0));
            }
            case "subscribe" -> {
                long id = m.path("marketId").asLong();
                MarketState ms = readModel.market(id);
                if (ms == null) return;
                c.books.add(id);
                synchronized (c) {
                    MarketState.Depth d = ms.depth(50);
                    c.lastSeq.put(id, d.seq());
                    send(c, book(d, true, 0));
                }
                send(c, Map.of("type", "trades", "marketId", id, "snapshot", true, "trades", ms.tape(60)));
            }
            case "unsubscribe" -> {
                long id = m.path("marketId").asLong();
                c.books.remove(id);
                c.lastSeq.remove(id);
            }
            case "ping" -> send(c, Map.of("type", "pong", "serverTime", System.currentTimeMillis()));
            default -> { }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        clients.remove(session.getId());
    }

    private void send(Client c, Object payload) {
        try {
            if (c.session.isOpen()) c.session.sendMessage(new TextMessage(json.writeValueAsString(payload)));
        } catch (IOException | RuntimeException e) {
            // Includes SessionLimitExceededException: slow consumer, drop it.
            clients.remove(c.session.getId());
            try {
                c.session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException ignored) {
            }
        }
    }
}
