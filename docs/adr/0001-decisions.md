# Architecture decision records

Each decision records its context, the choice made, and the trade-off accepted.

## ADR-0001: Java 21 core, native code only where measured
- **Context:** The core needs low latency *and* fast iteration, including frequent refactors of the matching and accounting rules.
- **Decision:** Java 21 for the engine, written in a mechanical-sympathy style: primitive collections, pooling, padded cursors, no locks. C++ is reserved for experiments that a benchmark justifies, wired in through FFM.
- **Trade-off:** We accept JIT warm-up and GC as risks to manage. Measured so far: GC causes one young pause per run with G1, and none with ZGC. The larger tail sources were OS timer oversleep and hash-table growth, not the JVM (see benchmarks.md).

## ADR-0002: Single-writer engine for all markets
- **Context:** Accounts span markets, and a BUY in market A reserves cash that market B must see.
- **Decision:** One thread owns every book and every account. Correctness comes from ownership, not locks.
- **Trade-off:** One core caps throughput (measured at ~2.7M cmd/s, far above paper-trading needs). If that cap matters later, partition markets across shards and give each shard a cash credit line drawn from a central ledger, like exchange margin pre-allocation. That is deferred until a measurement demands it.

## ADR-0003: Command sourcing with a binary journal
- **Context:** We need crash recovery, debugging by replay, and the ability to rebuild read models.
- **Decision:** Journal the *inputs* (commands), not the outputs. The engine is deterministic: ids come from counters, time comes from the command, and no hash-map iteration order is observable. That makes replay exact. The format is length + CRC32C framing over a compact little-endian binary body.
- **Trade-off:** Any non-determinism, such as a wall-clock read or HashMap ordering, would silently break recovery. This is mitigated by the digest test (same input gives the same 64-bit digest) and the replay-equality tests. Changing engine logic changes the meaning of old journals, so a version boundary must snapshot first.

## ADR-0004: One unified YES book per market
- **Context:** Separate YES and NO books leak liquidity. A YES buyer at 63¢ and a NO buyer at 37¢ should trade, by minting a pair.
- **Decision:** Translate all four intents into YES-price BID/ASK on a single book (see matching-engine.md).
- **Trade-off:** The accounting must handle mint, burn and transfer cases, plus auto-merge. That complexity is concentrated in `applyFill` and `mergePairs` and verified by the conservation invariant.

## ADR-0005: Integer ticks and mills, never floating point
- **Decision:** Prices are ticks of $0.001 in `1..999`, and cash is in mills. Products are exact, and cost-basis division rounds down, with full removal on close so flat positions carry no residue.
- **Trade-off:** Display formatting happens at the edges, and the UI must never do arithmetic on floats.

## ADR-0006: Journal as a parallel consumer, acks gated on durability
- **Context:** v1 journaled on the matching thread, which put syscall latency in series with matching.
- **Decision:** A broadcast ring with two consumers: the journaler and the matcher. Outputs are released only when `durable ≥ seq`.
- **Trade-off:** The matcher may run ahead of durability, so the outbound path must stage events per sequence. In exchange, matching never blocks on I/O and throughput rose 16%.
