package io.tradevision.gateway.projection;

import io.tradevision.engine.Outcome;
import io.tradevision.engine.Prices;
import io.tradevision.gateway.TvProperties;
import io.tradevision.gateway.catalog.MarketCatalog;
import io.tradevision.gateway.engine.EngineHost;
import io.tradevision.gateway.engine.Event;
import org.flywaydb.core.Flyway;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streams durable engine events into Postgres, <b>exactly once</b>, without ever slowing the engine down.
 *
 * <h2>Exactly-once</h2>
 * Each batch is written in one transaction that also stores the batch's last journal sequence in
 * {@code projection_offset}. A batch is applied completely or not at all, and events at or below the stored offset
 * are skipped. If anything fails (including the classic "did my COMMIT succeed?" when the connection drops
 * mid-commit), the writer reconnects and <i>re-reads the offset from the database</i> before retrying, so the
 * answer always comes from the database, never from guesswork.
 *
 * <h2>Never blocks the engine</h2>
 * The publisher thread only does a non-blocking {@code offer} into a bounded queue. One writer thread drains it
 * in batches. While Postgres is down the queue absorbs ~1M commands; past that, events are dropped and the
 * missing sequence range is written to {@code projection_gaps} when the database comes back. The journal
 * remains the source of truth either way.
 *
 * <h2>Catch-up after restart</h2>
 * On startup the engine replays the journal tail since its last snapshot. Those events arrive via
 * {@link #onReplay}; the writer skips everything already stored, so a crash between the engine journaling an
 * order and Postgres storing it heals itself on the next start.
 *
 * <h2>Write path</h2>
 * Trades (append-only, the hot table) are loaded with {@code COPY}. Positions and balances are <i>conflated</i>
 * per batch (only the last state per key is written), the same idea as the WebSocket conflation.
 */
@Component
@ConditionalOnProperty(name = "tradevision.postgres.enabled", havingValue = "true")
public class PostgresProjector implements EngineHost.Sink, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PostgresProjector.class);
    private static final String OFFSET_NAME = "core";
    private static final long BOT_ACCOUNT_FLOOR = 1_000_000;

    public enum State { STARTING, UP, DOWN }

    public record Status(State state, long offset, long queued, long batches, long rows, long lastBatchMicros,
                         long dropped, long gaps, String lastError) {}

    private record Batch(long seq, List<Event> events) {}

    private final DataSource ds;
    private final MarketCatalog catalog;
    private final int batchSize;
    private final ArrayBlockingQueue<Batch> queue = new ArrayBlockingQueue<>(1 << 20);

    private volatile boolean running;
    private volatile State state = State.STARTING;
    private volatile long offset = -1; // -1 = unknown: must be (re)read from the database
    private volatile String lastError;
    private volatile long lastBatchMicros;
    private final AtomicLong dropped = new AtomicLong();
    private long batches, rows, gaps;
    private Thread writer;

    public PostgresProjector(DataSource ds, MarketCatalog catalog, EngineHost engine, TvProperties props) {
        this.ds = ds;
        this.catalog = catalog;
        this.batchSize = props.postgres().batchSize() > 0 ? props.postgres().batchSize() : 2_000;
        engine.addSink(this); // registered before the engine starts, so recovery replay reaches us too
    }

    // ------------------------------------------------------------------ publisher thread (must not block)

    @Override
    public void onEvents(long sequence, List<Event> events) {
        enqueue(sequence, events);
    }

    @Override
    public void onReplay(long sequence, List<Event> events) {
        enqueue(sequence, events);
    }

    private void enqueue(long sequence, List<Event> events) {
        if (!queue.offer(new Batch(sequence, events))) dropped.incrementAndGet();
    }

    // ------------------------------------------------------------------ writer thread

    @Override
    public void start() {
        running = true;
        writer = Thread.ofPlatform().name("tv-pg-projector").daemon(true).start(this::run);
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
    // Lower phase than the engine (0): starts before it, stops after it, so the writer drains the last events the
    // engine published during shutdown.
    @Override public int getPhase() { return -10; }

    private void run() {
        List<Batch> work = new ArrayList<>(batchSize);
        long backoff = 250, lastLog = 0;
        while (running || !queue.isEmpty() || !work.isEmpty()) {
            try {
                if (offset < 0) connect();
                if (work.isEmpty()) {
                    Batch first = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (first == null) continue;
                    work.add(first);
                    queue.drainTo(work, batchSize - 1);
                }
                write(work);
                work.clear();
                backoff = 250;
                state = State.UP;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                state = State.DOWN;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                offset = -1; // outcome of the failed transaction is unknown: re-read the truth from the database
                if (System.currentTimeMillis() - lastLog > 30_000) {
                    log.warn("postgres projection unavailable, retrying (queued {}): {}", queue.size(), lastError);
                    lastLog = System.currentTimeMillis();
                }
                if (!running) return; // shutting down with the database unreachable: the journal still has it all
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, 5_000);
            }
        }
    }

    private void connect() throws SQLException {
        Flyway.configure().dataSource(ds).load().migrate(); // idempotent; runs V1.. once per database
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT seq FROM projection_offset WHERE name = ?")) {
            ps.setString(1, OFFSET_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                offset = rs.next() ? rs.getLong(1) : 0;
            }
        }
        log.info("postgres projection connected at offset {}", offset);
    }

    // ------------------------------------------------------------------ one transaction per batch

    private static final class MarketDelta {
        int lastPrice;
        long volume, notional, count, ts;
    }

    private void write(List<Batch> work) throws SQLException, java.io.IOException {
        long t0 = System.nanoTime();
        long expected = offset + 1;
        long gapFrom = 0, gapTo = 0, last = offset;

        List<Event.MarketCreated> marketsCreated = new ArrayList<>();
        Map<Long, Event.MarketStatusChanged> marketStatus = new LinkedHashMap<>();
        Map<Long, MarketDelta> marketTrades = new LinkedHashMap<>();
        Map<Long, Long> accountsCreated = new LinkedHashMap<>();
        Map<Long, Event.Balance> balances = new LinkedHashMap<>();
        List<Event.OrderAccepted> accepted = new ArrayList<>();
        Map<Long, long[]> fills = new LinkedHashMap<>(); // orderId -> {qty, value, ts}
        Map<Long, Event.OrderClosed> closed = new LinkedHashMap<>();
        StringBuilder tradesCsv = new StringBuilder();
        int tradeRows = 0;
        Map<Long, Map<Long, Event.PositionUpdate>> positions = new HashMap<>();
        List<long[]> settlements = new ArrayList<>(); // {seq, account, market, payout, ts}
        List<Object[]> rejections = new ArrayList<>();

        for (Batch b : work) {
            if (b.seq() < expected) continue; // already stored (replay overlap or retry after an ambiguous commit)
            if (b.seq() > expected && gapFrom == 0) {
                gapFrom = expected;
                gapTo = b.seq() - 1;
            }
            expected = b.seq() + 1;
            last = b.seq();
            for (Event e : b.events()) {
                switch (e) {
                    case Event.MarketCreated m -> marketsCreated.add(m);
                    case Event.MarketStatusChanged m -> marketStatus.put(m.marketId(), m);
                    case Event.AccountCreated a -> accountsCreated.putIfAbsent(a.accountId(), a.ts());
                    case Event.Balance x -> balances.put(x.accountId(), x);
                    case Event.OrderAccepted o -> accepted.add(o);
                    case Event.OrderClosed o -> closed.put(o.orderId(), o);
                    case Event.PositionUpdate p -> positions.computeIfAbsent(p.accountId(), k -> new HashMap<>()).put(p.marketId(), p);
                    case Event.Settlement s -> settlements.add(new long[] {b.seq(), s.accountId(), s.marketId(), s.payout(), s.ts()});
                    case Event.OrderRejected r -> rejections.add(new Object[] {b.seq(), r.accountId(), r.clientOrderId(),
                            r.marketId(), r.reason().name(), r.ts()});
                    case Event.Trade t -> {
                        addFill(fills, t.makerOrderId(), t.makerOutcome(), t.price(), t.quantity(), t.ts());
                        addFill(fills, t.takerOrderId(), t.takerOutcome(), t.price(), t.quantity(), t.ts());
                        MarketDelta d = marketTrades.computeIfAbsent(t.marketId(), k -> new MarketDelta());
                        d.lastPrice = t.price();
                        d.volume += t.quantity();
                        d.notional += (long) t.price() * t.quantity();
                        d.count++;
                        d.ts = t.ts();
                        tradesCsv.append(t.tradeId()).append(',').append(b.seq()).append(',').append(t.marketId()).append(',')
                                .append(t.price()).append(',').append(t.quantity()).append(',').append(t.aggressor()).append(',')
                                .append(t.makerOrderId()).append(',').append(t.makerAccountId()).append(',')
                                .append(t.makerOutcome()).append(',').append(t.makerAction()).append(',')
                                .append(t.takerOrderId()).append(',').append(t.takerAccountId()).append(',')
                                .append(t.takerOutcome()).append(',').append(t.takerAction()).append(',')
                                .append(instant(t.ts())).append('\n');
                        tradeRows++;
                    }
                    default -> { } // book levels, cancel/command rejections: not part of this projection
                }
            }
        }
        if (last == offset) return; // nothing new in this batch

        int n = 0;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (gapFrom != 0) {
                    try (var ps = c.prepareStatement("INSERT INTO projection_gaps (from_seq, to_seq) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                        ps.setLong(1, gapFrom);
                        ps.setLong(2, gapTo);
                        ps.executeUpdate();
                    }
                    gaps++;
                    log.warn("postgres projection gap: sequences {}..{} were never received", gapFrom, gapTo);
                }
                n += batch(c, """
                        INSERT INTO markets (market_id, question, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (market_id) DO NOTHING""", marketsCreated, (ps, m) -> {
                    var meta = catalog.get(m.marketId());
                    ps.setLong(1, m.marketId());
                    ps.setString(2, meta.map(MarketCatalog.MarketMeta::question).orElse(null));
                    ps.setString(3, meta.map(x -> x.source().name()).orElse(null));
                    ps.setTimestamp(4, ts(m.ts()));
                    ps.setTimestamp(5, ts(m.ts()));
                });
                n += batch(c, "UPDATE markets SET status = ?, winner = ?, updated_at = ? WHERE market_id = ?",
                        marketStatus.values(), (ps, m) -> {
                    ps.setString(1, m.status().name());
                    ps.setString(2, m.winner() == null ? null : m.winner().name());
                    ps.setTimestamp(3, ts(m.ts()));
                    ps.setLong(4, m.marketId());
                });
                n += batch(c, """
                        INSERT INTO accounts (account_id, is_bot, created_at, updated_at) VALUES (?, ?, ?, ?)
                        ON CONFLICT (account_id) DO NOTHING""", accountsCreated.entrySet(), (ps, a) -> {
                    ps.setLong(1, a.getKey());
                    ps.setBoolean(2, a.getKey() >= BOT_ACCOUNT_FLOOR);
                    ps.setTimestamp(3, ts(a.getValue()));
                    ps.setTimestamp(4, ts(a.getValue()));
                });
                n += batch(c, """
                        INSERT INTO accounts (account_id, available, reserved, is_bot, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (account_id) DO UPDATE
                        SET available = EXCLUDED.available, reserved = EXCLUDED.reserved, updated_at = EXCLUDED.updated_at""",
                        balances.values(), (ps, x) -> {
                    ps.setLong(1, x.accountId());
                    ps.setLong(2, x.available());
                    ps.setLong(3, x.reserved());
                    ps.setBoolean(4, x.accountId() >= BOT_ACCOUNT_FLOOR);
                    ps.setTimestamp(5, ts(x.ts()));
                    ps.setTimestamp(6, ts(x.ts()));
                });
                n += batch(c, """
                        INSERT INTO orders (order_id, client_order_id, account_id, market_id, outcome, action, tif,
                                            limit_price, quantity, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (order_id) DO NOTHING""", accepted, (ps, o) -> {
                    ps.setLong(1, o.orderId());
                    ps.setLong(2, o.clientOrderId());
                    ps.setLong(3, o.accountId());
                    ps.setLong(4, o.marketId());
                    ps.setString(5, o.outcome().name());
                    ps.setString(6, o.action().name());
                    ps.setString(7, o.tif().name());
                    ps.setInt(8, o.limitPrice());
                    ps.setLong(9, o.quantity());
                    ps.setTimestamp(10, ts(o.ts()));
                    ps.setTimestamp(11, ts(o.ts()));
                });
                n += batch(c, """
                        UPDATE orders SET filled = filled + ?, filled_value = filled_value + ?, updated_at = ?
                        WHERE order_id = ?""", fills.entrySet(), (ps, f) -> {
                    ps.setLong(1, f.getValue()[0]);
                    ps.setLong(2, f.getValue()[1]);
                    ps.setTimestamp(3, ts(f.getValue()[2]));
                    ps.setLong(4, f.getKey());
                });
                n += batch(c, "UPDATE orders SET status = ?, cancel_reason = ?, updated_at = ? WHERE order_id = ?",
                        closed.values(), (ps, o) -> {
                    ps.setString(1, o.filled() ? "FILLED" : "CANCELED");
                    ps.setString(2, o.reason() == null ? null : o.reason().name());
                    ps.setTimestamp(3, ts(o.ts()));
                    ps.setLong(4, o.orderId());
                });
                if (tradeRows > 0) {
                    // COPY streams rows in Postgres' bulk-load protocol: no per-row statement parse/plan/round trip.
                    c.unwrap(PGConnection.class).getCopyAPI().copyIn("""
                            COPY trades (trade_id, seq, market_id, price, quantity, aggressor, maker_order_id,
                                         maker_account_id, maker_outcome, maker_action, taker_order_id, taker_account_id,
                                         taker_outcome, taker_action, ts) FROM STDIN (FORMAT csv)""",
                            new StringReader(tradesCsv.toString()));
                    n += tradeRows;
                }
                n += batch(c, """
                        UPDATE markets SET last_price = ?, volume = volume + ?, notional = notional + ?,
                                           trade_count = trade_count + ?, updated_at = ?
                        WHERE market_id = ?""", marketTrades.entrySet(), (ps, m) -> {
                    MarketDelta d = m.getValue();
                    ps.setInt(1, d.lastPrice);
                    ps.setLong(2, d.volume);
                    ps.setLong(3, d.notional);
                    ps.setLong(4, d.count);
                    ps.setTimestamp(5, ts(d.ts));
                    ps.setLong(6, m.getKey());
                });
                List<Event.PositionUpdate> pos = new ArrayList<>();
                positions.values().forEach(m -> pos.addAll(m.values()));
                n += batch(c, """
                        INSERT INTO positions (account_id, market_id, yes, no, yes_locked, no_locked, yes_cost, no_cost,
                                               realized_pnl, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (account_id, market_id) DO UPDATE
                        SET yes = EXCLUDED.yes, no = EXCLUDED.no, yes_locked = EXCLUDED.yes_locked,
                            no_locked = EXCLUDED.no_locked, yes_cost = EXCLUDED.yes_cost, no_cost = EXCLUDED.no_cost,
                            realized_pnl = EXCLUDED.realized_pnl, updated_at = EXCLUDED.updated_at""", pos, (ps, p) -> {
                    ps.setLong(1, p.accountId());
                    ps.setLong(2, p.marketId());
                    ps.setLong(3, p.yes());
                    ps.setLong(4, p.no());
                    ps.setLong(5, p.yesLocked());
                    ps.setLong(6, p.noLocked());
                    ps.setLong(7, p.yesCost());
                    ps.setLong(8, p.noCost());
                    ps.setLong(9, p.realizedPnl());
                    ps.setTimestamp(10, ts(p.ts()));
                });
                n += batch(c, """
                        INSERT INTO settlements (seq, account_id, market_id, payout, ts) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT DO NOTHING""", settlements, (ps, s) -> {
                    ps.setLong(1, s[0]);
                    ps.setLong(2, s[1]);
                    ps.setLong(3, s[2]);
                    ps.setLong(4, s[3]);
                    ps.setTimestamp(5, ts(s[4]));
                });
                n += batch(c, """
                        INSERT INTO order_rejections (seq, account_id, client_order_id, market_id, reason, ts)
                        VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""", rejections, (ps, r) -> {
                    ps.setLong(1, (Long) r[0]);
                    ps.setLong(2, (Long) r[1]);
                    ps.setLong(3, (Long) r[2]);
                    ps.setLong(4, (Long) r[3]);
                    ps.setString(5, (String) r[4]);
                    ps.setTimestamp(6, ts((Long) r[5]));
                });
                try (var ps = c.prepareStatement("""
                        INSERT INTO projection_offset (name, seq, updated_at) VALUES (?, ?, now())
                        ON CONFLICT (name) DO UPDATE SET seq = EXCLUDED.seq, updated_at = now()""")) {
                    ps.setString(1, OFFSET_NAME);
                    ps.setLong(2, last);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | java.io.IOException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        offset = last;
        batches++;
        rows += n;
        lastBatchMicros = (System.nanoTime() - t0) / 1_000;
    }

    private static void addFill(Map<Long, long[]> fills, long orderId, Outcome outcome, int yesPrice, long qty, long ts) {
        long own = outcome == Outcome.YES ? yesPrice : Prices.complement(yesPrice);
        long[] f = fills.computeIfAbsent(orderId, k -> new long[3]);
        f[0] += qty;
        f[1] += own * qty;
        f[2] = ts;
    }

    @FunctionalInterface
    private interface Binder<T> {
        void bind(PreparedStatement ps, T row) throws SQLException;
    }

    private static <T> int batch(Connection c, String sql, Iterable<T> rows, Binder<T> binder) throws SQLException {
        int n = 0;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (T r : rows) {
                binder.bind(ps, r);
                ps.addBatch();
                n++;
            }
            if (n > 0) ps.executeBatch();
        }
        return n;
    }

    private static Timestamp ts(long micros) {
        return Timestamp.from(instant(micros));
    }

    private static Instant instant(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }

    // ------------------------------------------------------------------ observability

    public Status status() {
        return new Status(state, offset, queue.size(), batches, rows, lastBatchMicros, dropped.get(), gaps, lastError);
    }

    /** True once the projection has stored everything up to {@code sequence}. Used by tests and readiness checks. */
    public boolean caughtUpTo(long sequence) {
        return offset >= sequence;
    }
}
