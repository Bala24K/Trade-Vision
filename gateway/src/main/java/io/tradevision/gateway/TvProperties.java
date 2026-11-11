package io.tradevision.gateway;

import io.tradevision.engine.journal.Journal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradevision")
public record TvProperties(
        String dataDir,
        Journal.SyncPolicy syncPolicy,
        long snapshotEvery,
        /** FOLLOWER (default): a background engine replica snapshots off the hot path. INLINE: matcher pauses. */
        String snapshotMode,
        boolean busySpin,
        String adminToken,
        long startingBalanceDollars,
        Bots bots,
        Polymarket polymarket,
        Postgres postgres,
        ClickHouse clickhouse) {

    public record Bots(boolean enabled) {}

    public record Polymarket(boolean enabled, int markets, String restUrl, String wsUrl) {}

    /** Relational projection: orders, fills, positions, balances. Rebuildable from the journal. */
    public record Postgres(boolean enabled, String url, String user, String password, int batchSize) {}

    /** Columnar projection: trades, book samples and reference ticks for analytics. */
    public record ClickHouse(boolean enabled, String url, String database, String user, String password) {}
}
