package io.tradevision.bench;

import io.tradevision.engine.runtime.RingBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * N producers hand off messages to one consumer: custom MPSC RingBuffer vs ArrayBlockingQueue.
 * Measures end-to-end handoff throughput (messages/µs) for the gateway -> engine path.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class RingBufferBench {
    static final int MESSAGES = 2_000_000;

    @Param({"1", "4"})
    public int producers;

    @Benchmark
    @OperationsPerInvocation(MESSAGES)
    public long ringBuffer() throws InterruptedException {
        var ring = new RingBuffer<long[]>(1 << 14, () -> new long[1]);
        int per = MESSAGES / producers;
        var go = new CountDownLatch(1);
        for (int p = 0; p < producers; p++) {
            Thread.ofPlatform().start(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < per; i++) {
                    long s = ring.claim();
                    ring.get(s)[0] = i;
                    ring.publish(s);
                }
            });
        }
        go.countDown();
        long sum = 0, got = 0;
        long[] acc = new long[1];
        while (got < (long) per * producers) {
            int n = ring.poll((slot, seq) -> acc[0] += slot[0], 1024);
            if (n == 0) Thread.onSpinWait();
            got += n;
        }
        return sum + acc[0];
    }

    @Benchmark
    @OperationsPerInvocation(MESSAGES)
    public long arrayBlockingQueue() throws InterruptedException {
        var q = new ArrayBlockingQueue<long[]>(1 << 14);
        int per = MESSAGES / producers;
        var go = new CountDownLatch(1);
        for (int p = 0; p < producers; p++) {
            Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    for (int i = 0; i < per; i++) q.put(new long[] {i});
                } catch (InterruptedException ignored) {
                }
            });
        }
        go.countDown();
        long sum = 0;
        for (long got = 0; got < (long) per * producers; got++) sum += q.take()[0];
        return sum;
    }
}
