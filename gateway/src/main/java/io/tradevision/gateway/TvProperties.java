package io.tradevision.gateway;

import io.tradevision.engine.journal.Journal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradevision")
public record TvProperties(
        String dataDir,
        Journal.SyncPolicy syncPolicy,
        long snapshotEvery,
        boolean busySpin,
        String adminToken,
        long startingBalanceDollars,
        Bots bots,
        Polymarket polymarket) {

    public record Bots(boolean enabled) {}

    public record Polymarket(boolean enabled, int markets, String restUrl, String wsUrl) {}
}
