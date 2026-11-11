# Benchmarks & performance investigation

All numbers: Windows 11 desktop, 16 logical cores, JDK 23, `-Xms2g -Xmx2g`, no core isolation, other apps running.
They are **relative evidence for design decisions**, not exchange-grade claims. Reproduce with:

```bash
mvn -q install -DskipTests
java -jar bench/target/benchmarks.jar OrderBookBench          # JMH: data structures
java -jar bench/target/benchmarks.jar RingBufferBench         # JMH: inbound queue
java -Xms2g -Xmx2g -XX:+UseZGC -Dtv.busySpin=true -Dtv.presize=4194304 \
     -cp bench/target/benchmarks.jar io.tradevision.bench.LoadHarness paced 2000000 200000
```

## Headline (end-to-end, real pipeline incl. journal)

| Scenario | Result |
|---|---|
| Saturated throughput (4 producers → ring → journaler ‖ matcher) | **~2.7 M commands/s** |
| Matching cost per command (`apply`), saturated | **p50 203 ns · p99 0.8–1.4 µs** |
| Ingress → applied at 200k cmd/s (tuned) | **p50 < 1 µs · p99 46–67 µs · p99.9 ≈ 4.6 ms** |
| Same, with periodic snapshots: inline vs follower engine | **p99 528 ms → 54–104 µs**, 0 rejected orders |
| Linux container (8 pinned cores) | 2.05 M cmd/s saturated · p99 83–110 µs paced |
| Ingress → durable, OS page cache | p50 3 µs · p99 14 µs |
| Ingress → durable, `fsync` every batch | p50 0.7 ms · p99 2.8 ms (disk-bound, ~167 cmds share one fsync) |

## Micro: data structures (JMH, 1M-operation tape, ~45% cancels, ~10% marketable)

| Benchmark | ns/op | Notes |
|---|---|---|
| `TreeMap<price, ArrayDeque>` + `HashMap` (bare book, no accounting) | 102 ± 7 | textbook baseline |
| Full `MatchingEngine` (array levels + bitset + intrusive FIFO + pooled orders **+ risk, reservations, positions, events**) | 88 ± 45 | does strictly more work |

The engine is at least as fast while doing several times more work per operation. The baseline pays for O(log n)
tree walks, boxing (`Integer`/`Long` keys), and an O(queue length) scan to cancel from the middle of an `ArrayDeque`;
the engine replaces those with an array index, `LZCNT`/`TZCNT` bit scans and O(1) intrusive unlinking.
(The engine's error bar is wide; run with more forks before quoting a precise ratio.)

| Inbound queue (JMH, 2M messages) | 1 producer | 4 producers |
|---|---|---|
| `ArrayBlockingQueue` | 8.1 msg/µs | 8.5 msg/µs |
| `RingBuffer` (pre-allocated slots, CAS claim, release/acquire publish, padded cursors) | **27.3 msg/µs** | **20.8 msg/µs** |

## The tail-latency investigation

This is the part worth reading. Each step was a hypothesis, a measurement, and a decision.

| # | Change | Matching p99 | Ingress→applied p99 | p99.9 | max stall | Throughput |
|---|---|---|---|---|---|---|
| 0 | v1: journal appended + flushed **on the matching thread** | 2.8 µs | **6.7 ms** | 13.6 ms | 45 ms | 2.34 M/s |
| 1 | Journal becomes a **parallel ring consumer** (LMAX-style) | 4.2 µs | 7.1 ms | 14.4 ms | 39 ms | **2.71 M/s** |
| 2 | **Busy-spin** idle strategy on the matcher | 3.8 µs | **319 µs** | 10.2 ms | 43 ms | — |
| 3 | ZGC instead of G1 | 4.2 µs | 401 µs | 17.3 ms | 26 ms | — |
| 4 | **Pre-size the order-id index** | 4.9 µs | **46 µs** | 6.7 ms | **9 ms** | — |
| 5 | `-XX:+AlwaysPreTouch` | 4.6 µs | 67 µs | 4.6 ms | 7 ms | — |

**Step 1 — wrong hypothesis, right change.** I assumed the `write()` syscall on the matching thread caused the
6.7 ms p99. Moving journaling to a parallel consumer made durability latency excellent (p99 14 µs) and raised
throughput 16%, but *applied* latency did not move. The disk was not the problem. The change stayed because it is
architecturally correct (matching never waits on I/O), but it did not explain the tail.

**Step 2 — the real p99 culprit: the OS timer.** The back-off idle strategy parks for 50 µs when idle. On Windows,
`parkNanos` rounds up to the scheduler timer (1–15.6 ms). At 200k cmd/s the matcher keeps going idle for a few
microseconds between commands, parks, and oversleeps by milliseconds while the queue builds. Busy-spinning (one core
burned) cut p99 **26×**. Both strategies are kept: back-off for shared hosts, `-Dtv.busySpin=true` for a dedicated core.

**Step 3 — GC was not it either.** GC logging showed a single 40 ms G1 young pause in the whole run, which explains
the *max*, not a 0.1% tail. ZGC eliminated GC pauses (0 logged) but p99.9 stayed ≈ 17 ms, so something inside
`apply` was stalling.

**Step 4 — hash-table growth.** In this workload ~900k orders rest by the end (the harness cancels by guessed ids, so most
cancels miss). The open-order index doubles from 65k → 2M slots along the way, and each doubling is a
stop-the-world rehash on the engine thread (≈ 25–30 ms at 1M entries). Pre-sizing removed it: max stall 30 ms → 4 ms,
p99 311 µs → 46 µs. Production rule: size engine tables for peak, never grow on the hot path.

**What is left (p99.9 ≈ 4–7 ms):** most likely OS/VM preemption on a non-isolated machine. Step 8 re-measured on
Linux (a WSL2 container) and saw the same tail, which rules out the Windows timer as the cause. The next experiment
needs bare-metal Linux: `isolcpus` + thread affinity for matcher and journaler, `-XX:+UseTransparentHugePages`.

## Step 6: the same bug, opposite regime (found on the running server)

With the gateway and bots running at ~150 cmd/s, end-to-end latency was **p50 15.5 ms** although matching took ~1 µs.
At low load the threads are idle most of the time, park, and Windows oversleeps every park. Busy-spinning would fix it
but burns three cores on an idle server. Instead: **wake-on-submit**. A consumer raises `parked`, re-checks the ring,
then parks (1 ms safety timeout); producers unpark it after publishing. A full fence on both sides closes the
lost-wakeup window (Dekker-style StoreLoad ordering).

| Running server, ~150 cmd/s | before | after |
|---|---|---|
| ingress → applied p50 / p99 | 15.5 ms / 16.3 ms | **4 µs / 303 µs** |
| ingress → durable p50 | 15.5 ms | **7 µs** |

## Step 7: snapshots off the matching thread (follower engine)

Inline snapshots serialize all engine state on the matcher. In the paced harness about 1M orders are resting by the
end, so each snapshot takes **~680 ms**, and every order arriving meanwhile waits or, once the 65k-slot ring is full,
is rejected with "overloaded". `SnapshotFollower` replays the journal into a private engine copy and snapshots *that*
(ADR-0009).

Paced 200k cmd/s for 15 s, snapshot every 500k commands, busy-spin, ZGC, two runs each:

| | inline (matcher pauses) | follower (background) |
|---|---|---|
| ingress → applied p99 | **528 ms / 495 ms** | **104 µs / 54 µs** |
| ingress → applied max | 621 ms / 587 ms | 38 ms / 9 ms |
| orders rejected by backpressure | **115,638 / 99,421** | **0 / 0** |
| snapshot duration | ~680 ms each (on the matcher) | 672–685 ms each (off it) |

Same snapshot cost, but it moved to a thread nobody waits on. Reproduce with `-Dtv.snapshot=inline|follower`.

## Step 8: wake-on-submit also fixes the high-load case; Linux re-measure

Step 2 found that parking on Windows oversleeps by milliseconds, and busy-spin was the fix. Step 6 then added
wake-on-submit for low load. Re-running the paced 200k cmd/s test shows it also removes the reason to busy-spin at
high load:

| ingress → applied, paced 200k cmd/s | p99 | p99.9 |
|---|---|---|
| Windows, back-off idle **before** wake-on-submit (step 1) | 7.1 ms | 14.4 ms |
| Windows, busy-spin (step 4) | 46 µs | 6.7 ms |
| Windows, back-off idle **with** wake-on-submit | **51 µs** | 3.2 ms |
| Windows, busy-spin with wake-on-submit | 61 µs | 11.5 ms |
| Linux container (WSL2 kernel 6.6), 8 pinned cores, back-off, 2 runs | 94 / 83 µs | 6.9 / 5.4 ms |
| Linux container, 8 pinned cores, busy-spin, 2 runs | 86 / 110 µs | 5.1 / 7.6 ms |

| Saturated, 5M commands | throughput | match p50 / p99 |
|---|---|---|
| Windows host, 16 threads | 2.47 M cmd/s | 203 ns / 1.8 µs |
| Linux container, 8 pinned cores | 2.05 M cmd/s | 183 ns / 2.2 µs |

**What this settles:** the default (back-off + wake-on-submit) is now as good as burning a core, so busy-spin stays
an opt-in. **What it does not settle:** the 3–7 ms p99.9 appears on Linux too, so it is not the Windows timer. WSL2
is itself a Hyper-V VM, so `isolcpus` and thread affinity cannot be tested here. That still needs bare-metal Linux.

## Projection throughput

`PostgresProjectorTest` projects a 19,604-command random workload (1,955 trades, ~9k orders) into Postgres 16 in a
container in **2.1 s including Flyway migration and connection setup (~9k commands/s)**, in batches of 1,000
commands per transaction. The running compose stack shows ~6 ms per batch at bot load, with lag 0. The projection
is off the critical path, so its throughput bounds only how far it can fall behind, not trading.

## Correctness guards on the fast path

Speed claims are meaningless if the engine is wrong. Every change above was re-run against:
- 8 seeds × 15,000 random commands with **all global invariants checked after every command** (money conservation,
  YES/NO pair symmetry, reservations = open orders, locks = resting sells, non-crossed books, index integrity);
- determinism (same input → identical 64-bit state digest across runs);
- crash recovery (snapshot + journal replay → identical digest; torn-tail truncation; corrupt-snapshot fallback).
