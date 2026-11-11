# Roadmap

A phase is done only when it works, is tested, and has its decisions written down.

| Phase | Scope | Status |
|---|---|---|
| 0 | Domain model, units, event and command schema, ADRs | ✅ |
| 1 | Order book + matching engine: price-time priority, partial fills, all 4 YES/NO intents, GTC/IOC/FOK/POST_ONLY, STP | ✅ |
| 2 | Risk & portfolio: reservations, locks, auto-merge, realized/unrealized PnL, halts, resolution; invariant fuzzer | ✅ |
| 3 | Durability: binary command journal (CRC32C, group commit, torn-tail repair), snapshots, recovery | ✅ |
| 4 | Runtime: MPSC broadcast ring, parallel journaler + matcher, histogram, load harness, tail-latency investigation | ✅ |
| 5 | **Gateway**: Spring Boot REST + WS, HMAC tokens, idempotency keys, publisher gated on the durable sequence, CQRS read models, WS conflation + gap detection + slow-consumer eviction, wake-on-submit, HTTP integration test | ✅ |
| 6 | **Terminal UI**: market explorer (virtualized), live chart, order book ladder, trade tape, order ticket with honest optimistic states, positions/orders/history/fills, keyboard flows, connection/stale/RTT, metrics popover | ✅ |
| 7 | **Live data**: Polymarket CLOB discovery + WS ingestion (normalize, stale/dup drop, crossed-book resync, watchdog, backoff), reference quotes in UI | ✅ (ClickHouse tick store still open) |
| 8 | **Bots & sim**: market makers anchored to Polymarket mids (synthetic: OU walk + news jumps), noise traders | ✅ (recorded-session replay still open) |
| 9 | Analytics: VWAP, realized volatility, book imbalance, spread/depth analytics, leaderboard PnL over ClickHouse | |
| 10 | Performance II: Linux + isolcpus + affinity re-measure, follower-engine snapshots, cancel-by-client-id, C++/FFM comparison, SIMD depth aggregation | |
| 11 | Deploy: Docker Compose one-command up, CI benchmarks regression gate, public demo | |

## Known limitations (deliberate, for now)
- Snapshots are stop-the-world on the matcher thread. Fix: a follower engine replays the journal and snapshots off the hot path.
- All markets share one matcher thread. That is simple and deterministic, and measured at ~2.7M cmd/s. Partitioning markets across threads needs a solution for cross-market cash, e.g. per-shard credit allocation; see ADR-0002.
- No fees yet. The accounting hooks exist (`settleReserved`), but fee schedules don't.
- Cancel is by engine order id; cancel-by-client-order-id is still open.
- **No Postgres/ClickHouse yet.** Durable state is the journal + snapshots; read models are in memory, so trade history before the latest snapshot is not queryable. Next: an `EngineHost.Sink` that batches events into Postgres (orders/fills) and ClickHouse (ticks), rebuildable from the journal.
- Docker image is written but untested on this machine.
- The gateway allocates small event records on the matcher thread (the engine core does not). Profile before replacing them with ring-buffer flyweights.
