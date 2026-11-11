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
| 7 | **Live data**: Polymarket CLOB discovery + WS ingestion (normalize, stale/dup drop, crossed-book resync, watchdog, backoff), reference quotes in UI, 1 Hz book + reference samples in ClickHouse | ✅ |
| 8 | **Bots & sim**: market makers anchored to Polymarket mids (synthetic: OU walk + news jumps), noise traders | ✅ (recorded-session replay still open) |
| 9 | **Projections + analytics**: exactly-once Postgres projection (orders, fills, positions; keyset-paginated history; SQL leaderboard), ClickHouse trades + book samples, VWAP / realized vol / flow / spread / imbalance / Polymarket basis, in-memory fallback, Analytics + Leaderboard views, Testcontainers tests | ✅ |
| 10 | **Performance II**: follower-engine snapshots (p99 528 ms → 54 µs during snapshots), cancel-by-client-id (race-safe), Linux container re-measure | ✅ (bare-metal isolcpus, C++/FFM comparison, SIMD depth aggregation still open) |
| 11 | **Deploy**: Docker Compose one-command up (verified), CI compose smoke job | ✅ (public demo, CI benchmark regression gate still open) |

## Known limitations (deliberate, for now)
- All markets share one matcher thread. That is simple and deterministic, and measured at ~2.7M cmd/s. Partitioning markets across threads needs a solution for cross-market cash, e.g. per-shard credit allocation; see ADR-0002.
- No fees yet. The accounting hooks exist (`settleReserved`), but fee schedules don't.
- The follower engine doubles engine memory. Fine at paper-trading scale; at large scale the follower could run in a separate process reading the same journal.
- A Postgres projection attached to an exchange whose early journal segments were already compacted starts with a recorded gap (`projection_gaps`). A full historical rebuild would need journal archiving instead of deletion.
- The p99.9 tail (3–7 ms) persists on Linux-in-a-VM; bare-metal `isolcpus` is the next experiment.
- The gateway allocates small event records on the matcher thread (the engine core does not). Profile before replacing them with ring-buffer flyweights.
