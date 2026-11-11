# Projections: Postgres, ClickHouse and analytics

The engine's command journal is the only source of truth. Postgres and ClickHouse are **projections**: read-optimized
copies derived from durable engine events. They can lag, be stopped, or be rebuilt without touching the exchange.

```
 engine ─▶ EventTranslator ─▶ publisher (durable ≥ seq) ─┬─▶ ReadModel ─▶ REST / WebSocket (live)
                                                          ├─▶ MinuteBars (in-memory 24 h, 1-min buckets) ◀─ 1 Hz book sampler
                                                          ├─▶ PostgresProjector ─ bounded queue ─▶ writer ─▶ Postgres
                                                          └─▶ ClickHouseAnalytics ─ bounded queue ─▶ writer ─▶ ClickHouse
                                                                                                  ▲
                                                                       book sampler (1 Hz) ───────┘
```

The publisher thread only ever does a non-blocking `offer()` into each sink's queue. Database latency, outages and
slow queries never reach the matcher or the acknowledgement path.

## Postgres: exactly-once relational state

**Tables** ([V1__projection.sql](../gateway/src/main/resources/db/migration/V1__projection.sql)): `markets`,
`accounts`, `orders`, `trades`, `positions`, `settlements`, `order_rejections`, plus `projection_offset` and
`projection_gaps`. Migrations are managed by Flyway and run from the projector thread on first connection, so
the app starts even when Postgres is down.

**One transaction per batch.** The writer drains up to 2,000 commands and writes all their rows, plus
`projection_offset.seq = <last sequence in batch>`, in a single transaction. Rows are either all there with
their offset or not there at all.

**Exactly-once under failure.** Events at or below the stored offset are skipped. After *any* error, including the
classic ambiguous case where the connection dies during `COMMIT`, the writer reconnects and **re-reads the offset
from the database** before retrying. It never guesses whether the last commit landed.

**Catch-up after restart.** Recovery replays the journal tail since the last snapshot through the same translator;
those events go to `Sink.onReplay()`, and the projector skips what it already has. So "engine journaled it, then the
process died before Postgres stored it" heals on the next start.

**Honest gaps.** If Postgres is unreachable long enough to overflow the ~1M-command queue, or a brand-new database
is attached to an exchange whose old journal segments were already compacted, the missing sequence range is
written to `projection_gaps` and shown on `/api/system`.

**Write path.**
- `trades` (append-only, the hot table) is loaded with `COPY ... FROM STDIN`, Postgres' bulk-load protocol.
- `positions` and `accounts` are **conflated** per batch: only the last state per key is upserted.
- Order fills are aggregated per order and applied as `filled = filled + Δ`. That is safe only because of exactly-once.
- No foreign keys: integrity is guaranteed upstream, and FK checks would only add write cost.

**Read path.**
- **Keyset pagination** for history (`WHERE trade_id < :before ORDER BY trade_id DESC LIMIT n`). Page 1,000 costs the same as page 1. OFFSET would scan and discard every earlier row.
- **"My fills" when I can be maker or taker:** two narrow indexes, `(maker_account_id, trade_id DESC)` and `(taker_account_id, trade_id DESC)`, and a `UNION ALL` of two LIMITed index scans. `WHERE maker = ? OR taker = ?` would defeat the ordered scan.
- A **BRIN index** on `trades.ts`: trades arrive in time order, so a few KB of index prunes time-range scans over GBs.
- The **leaderboard** is one SQL query: realized PnL + mark-to-market of open positions at each market's last price, the same formula as the portfolio view.

**Verified** by [PostgresProjectorTest](../gateway/src/test/java/io/tradevision/gateway/projection/PostgresProjectorTest.java),
which runs against a real Postgres via Testcontainers:
- A random workload runs through a real engine and is projected.
- **Every account balance and position in Postgres equals the engine's**, open orders equal the engine's book, and filled orders are never over-filled.
- It then replays half the stream again and restarts the projector with the full stream replayed. All counts are unchanged.
- A deliberately skipped range shows up in `projection_gaps`.

## ClickHouse: analytics

**Tables**
- `trades`: `ReplacingMergeTree`, partitioned by month, ordered by `(market_id, trade_id)`.
- `book_samples`: best bid/ask, top-5 depth and the Polymarket reference, sampled once a second per market. It is a `MergeTree` with a 30-day TTL.

**Delivery.** ClickHouse has no multi-statement transactions, so the Postgres offset trick does not apply. Instead
storage is idempotent: a replayed trade is a duplicate row with the same key, which merges collapse, and queries
read `FINAL`. On startup the sink also skips trade ids at or below `max(trade_id)`, so duplicates are rare to begin with.

**Poison batches.** If ClickHouse rejects a batch's content (HTTP 4xx), retrying cannot help, and a retry loop
would block everything queued behind it. Such a batch is dropped, counted (`rejectedRows`) and logged. Network
errors and 5xx are retried with backoff. *Found by the full-stack test:* an early version serialized timestamps as
`1.79E9`, which ClickHouse rejected, and the retry loop then stalled every later sample.

**Queries** aggregate inside ClickHouse; only per-bucket results cross the network:
- per bucket: `argMin`/`argMax` open and close, `sumIf` buy and sell volume, notional for VWAP
- `avgIf` spread, depth imbalance and reference mid

Values are passed as typed server-side parameters (`{m:UInt32}` + `param_m=`), never concatenated into SQL.

## Metrics ([Buckets.java](../gateway/src/main/java/io/tradevision/gateway/analytics/Buckets.java))

| Metric | Definition |
|---|---|
| VWAP | Σ(price × qty) / Σqty over the window |
| Buy share | taker-buy volume / total volume (aggressor = BID) |
| Realized volatility | sample std-dev of close-to-close moves between *consecutive traded* buckets, × √(3600 / bucket seconds), in cents per √hour. Prices are bounded probabilities, so absolute moves are used, not log returns. Silent buckets break the chain instead of adding fake zero moves. |
| Spread | best ask − best bid (live, plus the window average of the 1 Hz samples) |
| Depth imbalance | (bid depth − ask depth) / (bid depth + ask depth) over the best 5 levels, in [−1, 1] |
| Polymarket basis | average of (bucket close − Polymarket mid) over traded buckets: how far the paper venue trades from the real market |

The same `Bucket` shape comes from ClickHouse or from the in-memory `MinuteBars` store, so metric code is shared and
unit-tested once. Every response carries `source` (`clickhouse`, `memory`, or `memory (clickhouse unavailable)`).
