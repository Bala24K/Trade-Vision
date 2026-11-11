# TradeVision architecture

TradeVision is a real-time prediction-market exchange and trading terminal. It runs a paper-trading venue with a
genuine matching engine, and it also ingests live public prediction-market data (Polymarket) as reference markets.

## System view (target)

```
                 ┌──────────────── Market data sources ───────────────┐
                 │ Polymarket CLOB WS · synthetic flow (sim) · replay │
                 └───────────────────────┬────────────────────────────┘
                                         ▼
                 ┌──────────── ingestion (Phase 3) ────────────┐
                 │ normalize · dedupe · gap-detect · resync    │──▶ ClickHouse (ticks, trades: billions of rows)
                 └──────────────────────┬──────────────────────┘
                                        ▼
 clients ──REST/WS──▶ gateway ──trySubmit──▶ inbound ring (seq = journal position)
   (React terminal)    (auth, validation,       │                          │
                        backpressure)            ▼ consumer 0              ▼ consumer 1
                                          journaler thread         matcher thread
                                          append + group commit    MatchingEngine (single writer)
                                          durable = seq ─────────▶ events staged per command
                                                                           │ released when durable ≥ seq
                                                                           ▼
                                            outbound fan-out: per-client conflation + backpressure
                                                    │                         │
                                                    ▼                         ▼
                                           WebSocket clients        Postgres projections
                                                                    (orders, trades, positions; batched COPY)
```

## Principles

1. **Single writer per state.** One thread owns all engine state, with no locks on the hot path (ADR-0002).
2. **The journal is the source of truth.** Engine state is a pure function of the command log. Snapshots bound recovery time. Postgres and ClickHouse are *projections* that can be rebuilt from the log (ADR-0003).
3. **Nothing leaves before it is durable.** Matching proceeds optimistically in parallel with journaling, but no acknowledgement or market-data event is released until the journaler's durable sequence covers it.
4. **Fail fast under overload.** The rings are bounded. When full, the gateway rejects with "overloaded" instead of queueing unboundedly.
5. **No network hop on the critical path.** The engine is in-process with the gateway. Splitting into services is a later decision, made only on measurements.
6. **Measure, then optimize.** Every performance change in [benchmarks.md](benchmarks.md) has a before/after number.

## Module map

| Module | Status | Contents |
|---|---|---|
| `engine` | **done** | Order book, matching, risk, positions/PnL, invariants, digest, journal, snapshots, recovery, ring buffer, runner, histogram. Zero dependencies. |
| `bench` | **done** | JMH benchmarks (book vs TreeMap, ring vs ABQ) and an end-to-end load harness. |
| `gateway` | **done** | Spring Boot: REST + WebSocket API, HMAC sessions, idempotent client order ids, durability-gated publisher, CQRS read models, WS conflation, bots, Polymarket ingestion. See [gateway.md](gateway.md). |
| `ingestion` | **done** (in gateway) | Polymarket CLOB discovery + WS: normalization, stale/duplicate dropping, crossed-book resync, watchdog, backoff. ClickHouse sink still planned. |
| `frontend` | **done** | React + TypeScript + Vite terminal: Zustand stores split by update frequency, Lightweight Charts, virtualized list, WS reconnect/stale/gap handling, honest optimistic orders, keyboard trading. |
| `native` | experiment | C++20 order book via Java FFM for a measured JVM-vs-native comparison, including SIMD depth aggregation. |

## Recovery

1. Load the newest snapshot whose CRC validates. If it is corrupt, fall back to the previous one.
2. Replay journal records with `seq > snapshot.seq` in order. A gap is a fatal error.
3. A torn final record left by a crash mid-write is detected by its length/CRC and truncated. It was never acknowledged.
4. Resume. The ring's sequence base is set to the journal's last sequence.

Verified by `RecoveryTest`: snapshot + replay produces the same 64-bit state digest as the live engine.
