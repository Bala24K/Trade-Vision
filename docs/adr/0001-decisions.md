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

## ADR-0007: Databases are projections, written exactly once, never on the critical path
- **Context:** History, leaderboards and analytics need real databases, but a database stall must never slow matching, and a database outage must never stop trading.
- **Decision:** Postgres and ClickHouse are fed by `EngineHost.Sink`s from the durable event stream. The publisher only does a non-blocking `offer` into a bounded queue; a writer thread per database drains it in batches. Postgres stores each batch and its last journal sequence in **one transaction** (`projection_offset`), skips anything at or below that offset, and after any failure re-reads the offset from the database before retrying. That gives exactly-once results even when a COMMIT's outcome is unknown. On restart, the engine's journal-tail replay is offered to the sinks, which skip what they already have.
- **Trade-off:** Projections lag the engine by a batch (milliseconds in practice; `/api/system` shows the lag). If Postgres is down longer than the ~1M-command buffer *and* the journal has since been compacted, the missing range is recorded in `projection_gaps`, not hidden. ClickHouse has no multi-statement transactions, so it uses idempotent storage instead (`ReplacingMergeTree` keyed by trade id, queries with `FINAL`).

## ADR-0008: Postgres for state, ClickHouse for analytics
- **Context:** Two different query shapes. "My last 50 fills" is a point lookup that must be exact and current. "VWAP, volatility and spread per 5 minutes for a week" scans millions of rows but touches 3–4 columns.
- **Decision:** Postgres holds orders, fills, positions and balances, with keyset-paginated history and narrow composite indexes (including a two-index `UNION ALL` for "fills where I am maker *or* taker"). ClickHouse holds trades and 1 Hz book samples, and analytics aggregate there. Only result rows cross the network. An in-memory 24 h minute-bucket store answers the same API when ClickHouse is off, and every response names its `source`.
- **Trade-off:** Two databases to operate. Docker Compose hides that locally; both are optional at runtime.

## ADR-0009: Snapshots by a follower engine
- **Context:** An inline snapshot serializes all engine state on the matcher thread. With ~1M resting orders that is a pause of tens to hundreds of milliseconds for every order queued behind it.
- **Decision:** A second, private engine (`SnapshotFollower`) tails the journal with `JournalTailer`, applies commands up to the durable sequence, and snapshots its own copy; it also prunes old snapshots and compacts journal segments it has fully passed. Correctness rests on determinism, the same property recovery already depends on, and is tested by digest equality.
- **Trade-off:** A second copy of engine state in memory and a mostly idle core. Inline mode is still available (`tradevision.snapshot-mode=INLINE`).
