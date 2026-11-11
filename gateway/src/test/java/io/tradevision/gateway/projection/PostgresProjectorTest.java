package io.tradevision.gateway.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradevision.engine.Command;
import io.tradevision.engine.MatchingEngine;
import io.tradevision.engine.account.Account;
import io.tradevision.engine.account.Position;
import io.tradevision.engine.journal.Journal;
import io.tradevision.engine.sim.RandomWorkload;
import io.tradevision.gateway.TvProperties;
import io.tradevision.gateway.catalog.MarketCatalog;
import io.tradevision.gateway.engine.EngineHost;
import io.tradevision.gateway.engine.Event;
import io.tradevision.gateway.engine.EventTranslator;
import io.tradevision.gateway.read.ReadModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a real engine with a random workload, streams its events through the projector into a real Postgres,
 * then proves the projection is (1) exactly equal to engine state, (2) unaffected by replays and restarts, and
 * (3) honest about gaps.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresProjectorTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.6-alpine");

    record Cmd(long seq, List<Event> events) {}

    static MatchingEngine engine;
    static final List<Cmd> stream = new ArrayList<>();
    static long trades, accepted;

    @TempDir
    Path tmp;
    DataSource ds;
    JdbcTemplate jdbc;
    PostgresProjector projector;

    @BeforeAll
    static void generate() {
        var translator = new EventTranslator((seq, corr, events) -> stream.add(new Cmd(seq, events)));
        engine = new MatchingEngine(translator);
        long[] seq = {0};
        Consumer<Command> sink = c -> {
            c.sequence = ++seq[0];
            c.timestamp = 1_780_000_000_000_000L + seq[0] * 1_000;
            translator.onCommandStart(c);
            c.applyTo(engine);
            translator.onCommandEnd(c);
        };
        var w = new RandomWorkload(42, 4, 25);
        w.setup(sink);
        for (int i = 0; i < 20_000; i++) w.step(engine, sink);
        for (Cmd c : stream) {
            for (Event e : c.events()) {
                if (e instanceof Event.Trade) trades++;
                if (e instanceof Event.OrderAccepted) accepted++;
            }
        }
        assertTrue(trades > 1_000, "workload must actually trade: " + trades);
    }

    @BeforeEach
    void setUp() {
        var cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        ds = new com.zaxxer.hikari.HikariDataSource(cfg);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;"); // fresh database per test
    }

    @AfterEach
    void tearDown() {
        if (projector != null) projector.stop();
        ((com.zaxxer.hikari.HikariDataSource) ds).close();
    }

    PostgresProjector newProjector() {
        var props = new TvProperties(tmp.toString(), Journal.SyncPolicy.OS_CACHE, 0, "INLINE", false, "x", 0,
                new TvProperties.Bots(false), new TvProperties.Polymarket(false, 0, "", ""),
                new TvProperties.Postgres(true, PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), 1_000), null);
        var host = new EngineHost(props, new ReadModel()); // only used to register the sink; never started
        var p = new PostgresProjector(ds, new MarketCatalog(props, new ObjectMapper()), host, props);
        p.start();
        return p;
    }

    void feed(PostgresProjector p, int from, int to, boolean replay) {
        for (int i = from; i < to; i++) {
            Cmd c = stream.get(i);
            if (replay) p.onReplay(c.seq(), c.events());
            else p.onEvents(c.seq(), c.events());
        }
    }

    void await(PostgresProjector p, long seq) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!p.caughtUpTo(seq) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(p.caughtUpTo(seq), "projection reached " + seq + ", status " + p.status());
    }

    long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void projectionEqualsEngineState_exactlyOnceAcrossReplayAndRestart() throws Exception {
        int n = stream.size(), half = n / 2;
        long t0 = System.nanoTime();
        projector = newProjector();
        feed(projector, 0, n, false);
        await(projector, stream.get(n - 1).seq());
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("projected %,d commands (%,d trades) in %.2f s = %,.0f commands/s%n", n, trades, secs, n / secs);

        assertMatchesEngine();

        // Replay overlap (what a restart does): everything already stored must be skipped, not double-counted.
        feed(projector, half, n, true);
        Thread.sleep(500);
        assertMatchesEngine();

        // Restart: a brand-new projector reads its offset from the database and ignores the replayed prefix.
        projector.stop();
        projector = newProjector();
        feed(projector, 0, n, true);
        Thread.sleep(500);
        assertEquals(stream.get(n - 1).seq(), projector.status().offset());
        assertMatchesEngine();
    }

    @Test
    void missingSequencesAreRecordedAsAGap() throws Exception {
        projector = newProjector();
        feed(projector, 0, 100, false);
        // Simulate events lost while the database was unreachable for too long: skip commands 100..149.
        feed(projector, 150, 200, false);
        await(projector, stream.get(199).seq());
        assertEquals(1, count("SELECT count(*) FROM projection_gaps"));
        assertEquals(stream.get(100).seq(), count("SELECT from_seq FROM projection_gaps"));
        assertEquals(stream.get(149).seq(), count("SELECT to_seq FROM projection_gaps"));
    }

    void assertMatchesEngine() {
        assertEquals(trades, count("SELECT count(*) FROM trades"), "every trade exactly once");
        assertEquals(accepted, count("SELECT count(*) FROM orders"), "every accepted order exactly once");
        assertEquals(engine.openOrders().size(), count("SELECT count(*) FROM orders WHERE status = 'OPEN'"),
                "open orders match the engine's book");
        assertEquals(0, count("SELECT count(*) FROM orders WHERE status = 'FILLED' AND filled <> quantity"),
                "filled orders are fully filled");
        assertEquals(0, count("SELECT count(*) FROM orders WHERE filled > quantity"), "no double-counted fills");
        for (long id : engine.accounts().sortedKeys()) {
            Account a = engine.account(id);
            var row = jdbc.queryForMap("SELECT available, reserved FROM accounts WHERE account_id = ?", id);
            assertEquals(a.available(), ((Number) row.get("available")).longValue(), "available of " + id);
            assertEquals(a.reserved(), ((Number) row.get("reserved")).longValue(), "reserved of " + id);
            for (long m : a.positions().sortedKeys()) {
                Position p = a.position(m);
                var pr = jdbc.queryForMap("SELECT * FROM positions WHERE account_id = ? AND market_id = ?", id, m);
                assertEquals(p.yes(), ((Number) pr.get("yes")).longValue());
                assertEquals(p.no(), ((Number) pr.get("no")).longValue());
                assertEquals(p.yesCost(), ((Number) pr.get("yes_cost")).longValue());
                assertEquals(p.noCost(), ((Number) pr.get("no_cost")).longValue());
                assertEquals(p.realizedPnl(), ((Number) pr.get("realized_pnl")).longValue());
            }
        }
    }
}
