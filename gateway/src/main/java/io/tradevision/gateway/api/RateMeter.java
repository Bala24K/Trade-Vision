package io.tradevision.gateway.api;

import io.tradevision.engine.runtime.LatencyHistogram;
import io.tradevision.gateway.engine.EngineHost;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Samples engine throughput once a second, and resets the latency histograms every minute so the
 * dashboard shows recent behaviour rather than an all-time distribution dominated by startup.
 */
@Component
@EnableScheduling
public class RateMeter {
    private final EngineHost engine;
    private long lastProcessed = -1;
    private volatile long perSecond;
    private int ticks;

    public RateMeter(EngineHost engine) {
        this.engine = engine;
    }

    @Scheduled(fixedRate = 1000)
    void sample() {
        if (engine.runner() == null) return;
        long p = engine.runner().processed();
        if (lastProcessed >= 0) perSecond = p - lastProcessed;
        lastProcessed = p;
        if (++ticks % 60 == 0) {
            for (LatencyHistogram h : new LatencyHistogram[] {engine.runner().applyTime(), engine.runner().ingressToApplied(),
                    engine.runner().ingressToDurable()}) h.reset();
        }
    }

    public long perSecond() {
        return perSecond;
    }
}
