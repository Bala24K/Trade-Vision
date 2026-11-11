package io.tradevision.gateway.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradevision.gateway.TvProperties;
import io.tradevision.gateway.engine.EngineHost;
import io.tradevision.gateway.engine.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Columnar projection for analytics. Two tables:
 * <ul>
 *   <li>{@code trades}: every durable trade (from the engine event stream, journal-derived).</li>
 *   <li>{@code book_samples}: 1 Hz top-of-book + depth + Polymarket reference per market (telemetry, sampled).</li>
 * </ul>
 *
 * <h2>Why ClickHouse next to Postgres</h2>
 * Postgres answers "what are my orders" (point lookups, transactions). Analytics asks "VWAP, volatility and
 * spread per minute over the last week for one market": scans of millions of rows touching 3-4 columns.
 * A column store reads only those columns, compressed, and aggregates them vectorized.
 *
 * <h2>Delivery semantics</h2>
 * ClickHouse has no multi-statement transactions, so Postgres' exact-once offset trick does not apply. Instead
 * {@code trades} is a {@code ReplacingMergeTree} keyed by trade id: a replayed or retried insert creates a
 * duplicate row that background merges collapse, and queries read with {@code FINAL} to get exact answers before
 * that. On startup the sink also skips trades at or below {@code max(trade_id)}, so duplicates are rare to begin with.
 */
@Component
@ConditionalOnProperty(name = "tradevision.clickhouse.enabled", havingValue = "true")
public class ClickHouseAnalytics implements EngineHost.Sink, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseAnalytics.class);

    public record Status(boolean up, long tradesWritten, long samplesWritten, long queued, long dropped,
                         long rejectedRows, long lastFlushMicros, String lastError) {}

    private final ClickHouseClient ch;
    private record TradeRow(long seq, Event.Trade t) {}

    private final ArrayBlockingQueue<TradeRow> trades = new ArrayBlockingQueue<>(1 << 20);
    private final ArrayBlockingQueue<BookSample> samples = new ArrayBlockingQueue<>(1 << 18);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile boolean running, up;
    private volatile long maxStoredTradeId = -1, tradesWritten, samplesWritten, lastFlushMicros;
    private volatile String lastError;
    private Thread writer;

    public ClickHouseAnalytics(TvProperties props, ObjectMapper json, EngineHost engine, MinuteBars bars) {
        this.ch = new ClickHouseClient(props.clickhouse(), json);
        engine.addSink(this);
        bars.onSamples(batch -> {
            for (BookSample s : batch) if (!samples.offer(s)) dropped.incrementAndGet();
        });
    }

    // ------------------------------------------------------------------ inputs (publisher / sampler threads)

    @Override
    public void onEvents(long sequence, List<Event> events) {
        for (Event e : events) {
            if (e instanceof Event.Trade t && !trades.offer(new TradeRow(sequence, t))) dropped.incrementAndGet();
        }
    }

    @Override
    public void onReplay(long sequence, List<Event> events) {
        onEvents(sequence, events);
    }

    // ------------------------------------------------------------------ writer

    @Override
    public void start() {
        running = true;
        writer = Thread.ofPlatform().name("tv-clickhouse").daemon(true).start(this::run);
    }

    @Override
    public void stop() {
        running = false;
        try {
            if (writer != null) writer.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return -10; }

    private void run() {
        List<TradeRow> tradeBatch = new ArrayList<>();
        List<BookSample> sampleBatch = new ArrayList<>();
        long backoff = 500, lastLog = 0;
        while (running || !trades.isEmpty()) {
            try {
                if (!up) init();
                if (tradeBatch.isEmpty()) trades.drainTo(tradeBatch, 50_000);
                if (sampleBatch.isEmpty()) samples.drainTo(sampleBatch, 50_000);
                if (tradeBatch.isEmpty() && sampleBatch.isEmpty()) {
                    Thread.sleep(500); // ClickHouse prefers few large inserts over many small ones
                    continue;
                }
                long t0 = System.nanoTime();
                // Each batch is retried until it succeeds, except when ClickHouse rejects its content (4xx):
                // such a "poison" batch would fail forever and block everything queued behind it.
                if (deliver(() -> writeTrades(tradeBatch), tradeBatch.size())) tradeBatch.clear();
                if (deliver(() -> writeSamples(sampleBatch), sampleBatch.size())) sampleBatch.clear();
                lastFlushMicros = (System.nanoTime() - t0) / 1_000;
                backoff = 500;
                if (running) Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                up = false;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (System.currentTimeMillis() - lastLog > 30_000) {
                    log.warn("clickhouse unavailable, retrying: {}", lastError);
                    lastLog = System.currentTimeMillis();
                }
                if (!running) return;
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, 10_000);
            }
        }
    }

    @FunctionalInterface
    private interface Write {
        void run() throws IOException;
    }

    /** @return true if the batch is done (written or permanently rejected); throws for retryable failures. */
    private boolean deliver(Write w, int rows) throws IOException {
        if (rows == 0) return true;
        try {
            w.run();
            return true;
        } catch (ClickHouseClient.ClickHouseException e) {
            if (e.retryable()) throw e;
            rejected.addAndGet(rows);
            lastError = e.getMessage();
            log.error("clickhouse rejected a batch of {} rows, dropping it: {}", rows, e.getMessage());
            return true;
        }
    }

    private void init() throws IOException {
        ch.execute("CREATE DATABASE IF NOT EXISTS " + ch.database(), false);
        ch.execute("""
                CREATE TABLE IF NOT EXISTS trades (
                    trade_id      UInt64,
                    seq           UInt64,
                    market_id     UInt32,
                    ts            DateTime64(6, 'UTC'),
                    price         UInt16,
                    quantity      UInt64,
                    aggressor     LowCardinality(String),
                    maker_account UInt64,
                    taker_account UInt64
                ) ENGINE = ReplacingMergeTree
                PARTITION BY toYYYYMM(ts)
                ORDER BY (market_id, trade_id)""", true);
        ch.execute("""
                CREATE TABLE IF NOT EXISTS book_samples (
                    market_id UInt32,
                    ts        DateTime64(3, 'UTC'),
                    best_bid  Int16,
                    best_ask  Int16,
                    bid_depth UInt64,
                    ask_depth UInt64,
                    ref_bid   Int16,
                    ref_ask   Int16
                ) ENGINE = MergeTree
                PARTITION BY toYYYYMMDD(ts)
                ORDER BY (market_id, ts)
                TTL toDateTime(ts) + INTERVAL 30 DAY""", true);
        List<JsonNode> r = ch.query("SELECT max(trade_id) AS m FROM trades", Map.of());
        maxStoredTradeId = r.isEmpty() ? 0 : r.get(0).path("m").asLong();
        up = true;
        lastError = null;
        log.info("clickhouse analytics connected (max stored trade id {})", maxStoredTradeId);
    }

    private void writeTrades(List<TradeRow> batch) throws IOException {
        StringBuilder sb = new StringBuilder(batch.size() * 200);
        int n = 0;
        long max = maxStoredTradeId;
        for (TradeRow row : batch) {
            Event.Trade t = row.t();
            if (t.tradeId() <= maxStoredTradeId) continue;
            sb.append("{\"trade_id\":").append(t.tradeId())
              .append(",\"seq\":").append(row.seq()).append(",\"market_id\":").append(t.marketId())
              .append(",\"ts\":\"").append(ts(t.ts())).append('"')
              .append(",\"price\":").append(t.price())
              .append(",\"quantity\":").append(t.quantity())
              .append(",\"aggressor\":\"").append(t.aggressor()).append('"')
              .append(",\"maker_account\":").append(t.makerAccountId())
              .append(",\"taker_account\":").append(t.takerAccountId()).append("}\n");
            max = Math.max(max, t.tradeId());
            n++;
        }
        ch.insert("trades", sb);
        maxStoredTradeId = max;
        tradesWritten += n;
    }

    private void writeSamples(List<BookSample> batch) throws IOException {
        StringBuilder sb = new StringBuilder(batch.size() * 140);
        for (BookSample s : batch) {
            sb.append("{\"market_id\":").append(s.marketId())
              .append(",\"ts\":\"").append(ts(s.tsMillis() * 1_000)).append('"')
              .append(",\"best_bid\":").append(s.bestBid())
              .append(",\"best_ask\":").append(s.bestAsk())
              .append(",\"bid_depth\":").append(s.bidDepth())
              .append(",\"ask_depth\":").append(s.askDepth())
              .append(",\"ref_bid\":").append(s.refBid())
              .append(",\"ref_ask\":").append(s.refAsk()).append("}\n");
        }
        ch.insert("book_samples", sb);
        samplesWritten += batch.size();
    }

    /** "2026-11-11 10:15:42.123456" in UTC, the DateTime64 text format. */
    private static String ts(long micros) {
        String iso = Instant.EPOCH.plus(micros, ChronoUnit.MICROS).toString(); // 2026-11-11T10:15:42.123456Z
        return iso.replace('T', ' ').replace("Z", "");
    }

    // ------------------------------------------------------------------ queries

    /**
     * Per-bucket trade and book aggregates over any window, computed inside ClickHouse (only the result rows
     * cross the network). Same {@link Bucket} shape as the in-memory store.
     */
    public List<Bucket> buckets(long marketId, long fromSec, int bucketSeconds) throws IOException {
        Map<String, Object> p = Map.of("m", marketId, "from", fromSec, "b", bucketSeconds);
        TreeMap<Long, Bucket> out = new TreeMap<>();
        for (JsonNode r : ch.query("""
                SELECT toUnixTimestamp(toStartOfInterval(ts, toIntervalSecond({b:UInt32}))) AS t,
                       argMin(price, trade_id) AS open, max(price) AS high, min(price) AS low,
                       argMax(price, trade_id) AS close,
                       sum(quantity) AS volume,
                       sumIf(quantity, aggressor = 'BID') AS buy, sumIf(quantity, aggressor = 'ASK') AS sell,
                       sum(toUInt64(price) * quantity) AS notional, count() AS trades
                  FROM trades FINAL
                 WHERE market_id = {m:UInt32} AND ts >= toDateTime64({from:Int64}, 6, 'UTC')
                 GROUP BY t ORDER BY t""", p)) {
            long t = r.get("t").asLong();
            out.put(t, new Bucket(t, r.get("open").asInt(), r.get("high").asInt(), r.get("low").asInt(),
                    r.get("close").asInt(), r.get("volume").asLong(), r.get("buy").asLong(), r.get("sell").asLong(),
                    r.get("notional").asLong(), r.get("trades").asLong(), Double.NaN, Double.NaN, Double.NaN));
        }
        for (JsonNode r : ch.query("""
                SELECT toUnixTimestamp(toStartOfInterval(ts, toIntervalSecond({b:UInt32}))) AS t,
                       avgIf(best_ask - best_bid, best_bid > 0 AND best_ask > 0) AS spread,
                       avgIf((toInt64(bid_depth) - toInt64(ask_depth)) / (bid_depth + ask_depth),
                             bid_depth + ask_depth > 0) AS imbalance,
                       avgIf((ref_bid + ref_ask) / 2, ref_bid > 0 AND ref_ask > 0) AS ref_mid
                  FROM book_samples
                 WHERE market_id = {m:UInt32} AND ts >= toDateTime64({from:Int64}, 3, 'UTC')
                 GROUP BY t ORDER BY t""", p)) {
            long t = r.get("t").asLong();
            Bucket b = out.getOrDefault(t, new Bucket(t, -1, -1, -1, -1, 0, 0, 0, 0, 0, Double.NaN, Double.NaN, Double.NaN));
            out.put(t, new Bucket(t, b.open(), b.high(), b.low(), b.close(), b.volume(), b.buyVolume(), b.sellVolume(),
                    b.notional(), b.trades(), num(r.get("spread")), num(r.get("imbalance")), num(r.get("ref_mid"))));
        }
        return new ArrayList<>(out.values());
    }

    private static double num(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? Double.NaN : n.asDouble();
    }

    public Status status() {
        return new Status(up, tradesWritten, samplesWritten, trades.size() + samples.size(), dropped.get(),
                rejected.get(), lastFlushMicros, lastError);
    }
}
