# Gateway, live data and terminal

The `gateway` module wraps the dependency-free engine in a Spring Boot process. It provides REST and WebSocket APIs, CQRS read models, bots, and live Polymarket ingestion. The `frontend` module is the React trading terminal, and its production build is served by the same jar.

## Request path and the durability gate

```
POST /api/orders ─▶ OrderService (validate, idempotency key) ─▶ EngineHost.submit()
      ▲                                                             │ trySubmit (503 if ring full)
      │                                                             ▼
      │                         inbound ring ─▶ journaler ─ durable seq ─┐
      │                                     └─▶ matcher ─▶ EventTranslator│ (copies engine callbacks
      │                                                    │              │  into immutable records,
      │                                                    ▼              │  grouped per command)
      │                                           outbound queue          │
      │                                                    │              ▼
      └─── HTTP response ◀── future.complete ◀── publisher: wait(durable ≥ seq) ─▶ ReadModel ─▶ WS hub
```

- Every command carries a correlation id. The HTTP response is built from **exactly the events that command caused**, and only once they are durable. A client never sees a fill that a crash and replay could not reproduce.
- **Idempotency:** `clientOrderId` is the key per account. A retry returns the original result instead of trading twice. This is tested over HTTP.
- **Backpressure:** a full ring returns `503 OVERLOADED` with `Retry-After` immediately, rather than an unbounded queue.
- **CQRS:** HTTP and WebSocket threads never touch engine state. They read `ReadModel`, which is rebuilt from the durable event stream. On start, state is seeded from the recovered engine and the replayed journal tail rebuilds chart history.

## REST

| Route | Purpose |
|---|---|
| `POST /api/session` | create a paper account ($10k) and return a stateless HMAC token |
| `GET /api/markets`, `/api/markets/{id}`, `/api/markets/{id}/candles?tf=5s\|1m\|15m` | catalog + stats + Polymarket reference, depth + tape, OHLCV |
| `GET /api/me`, `/api/me/orders?status=open\|history`, `/api/me/fills` | portfolio with mark-to-market PnL, orders, fills |
| `POST /api/orders` | `{marketId, outcome: YES\|NO, side: BUY\|SELL, type: LIMIT\|MARKET, price (ticks), quantity, tif, clientOrderId}` |
| `DELETE /api/orders/{id}`, `DELETE /api/orders?marketId=` | cancel one, cancel all |
| `DELETE /api/orders/client/{clientOrderId}` | cancel by the client's own id; if that order is still in flight, waits for its durable result, then cancels it |
| `GET /api/me/history/fills?before=&limit=`, `/api/me/history/orders?before=&limit=` | full history, keyset-paginated (Postgres; falls back to the in-memory window) |
| `GET /api/analytics/markets/{id}?window=60` | VWAP, realized vol, flow, spread, imbalance, Polymarket basis + buckets (ClickHouse or memory) |
| `GET /api/analytics/scanner`, `/api/analytics/leaderboard?bots=false` | last-hour metrics for every market; PnL leaderboard (Postgres or memory) |
| `POST /api/admin/markets/{id}/halt\|reopen\|resolve?winner=YES` | admin, `X-Admin-Token` header |
| `GET /api/system` | throughput, latency percentiles, queue depths, recovery, ingestion, snapshot follower, projection lag/status |

## WebSocket protocol (`/ws`)

Client → server: `{"op":"auth","token"}`, `{"op":"subscribe","marketId"}`, `{"op":"unsubscribe","marketId"}`, `{"op":"ping"}`.

| Server message | Cadence | Notes |
|---|---|---|
| `tickers` | every 250 ms, changed markets only | full list on connect |
| `book` | snapshot on subscribe, then ≤ 1 delta per 50 ms per market | `seq` + `prevSeq`; absolute level values |
| `trades` | batched per 50 ms | snapshot of the last 60 on subscribe |
| `account` | immediate | a "your account changed" notification; the client debounces one refetch |

**Conflation.** Book changes are only *marked dirty* on the publisher thread. The flush thread reads current quantities and the book sequence atomically, so a delta with `seq > S` is always at least as new as a snapshot taken at `S`. Intermediate states are dropped safely, because values are absolute.

**Gap detection.** A client applies a delta only if `prevSeq` equals its current sequence. Otherwise it resubscribes for a fresh snapshot. A smoke test over the live server saw 0 gaps and 0 crossed local books.

**Slow consumers.** Each session is wrapped in a bounded decorator (2 s send limit, 1 MB buffer). A client that can't keep up is disconnected, so it never slows down anyone else.

## Live Polymarket ingestion

- **Discovery** uses the CLOB REST API, picking active binary markets with a price between 7¢ and 93¢, varied by topic. The Gamma API was unreachable from the dev network, so the code uses only the CLOB.
- **Streaming** uses the public market channel. Decimal-string prices are normalized to integer ticks. Both the legacy `changes[]` and current `price_changes[]` delta formats are accepted.
- **Integrity without sequence numbers.** The feed has none, so:
  - every `book` message is an authoritative snapshot
  - deltas older than the snapshot are dropped as **stale**
  - a repeated hash is dropped as a **duplicate**
  - a **crossed** mirrored book triggers a resync by resubscribing, at most once per 30 s
- **Self-healing:** a `PING` every 10 s, a watchdog that kills a connection silent for 60 s, and exponential backoff with jitter. This was observed in practice: an SSL handshake failure on one restart recovered by itself.
- **Use:** reference quotes are shown in the UI as a dashed "Polymarket" price line and quote in the header. The market-making bots anchor their fair value to the Polymarket mid, so mirrored paper markets track the real ones (observed: our 10.7/11.0¢ against Polymarket's 10.9/11.0¢).

## Bots

Each market has one market maker and three noise traders. They use the same engine path as humans, with no shortcuts.

- **Market maker:** quotes a 5-level ladder. Bids are BUY YES and asks are BUY NO at the complement, so it never needs inventory; that is the unified book at work. It re-quotes only when fair value moves 3+ ticks or its quotes get hit, which preserves queue priority.
- **Noise traders:** mostly small marketable IOC orders, some passive limits, and they sell inventory back.
- **Synthetic fair value:** a mean-reverting random walk with rare news jumps.

## Terminal (React + TypeScript)

- **State split by update frequency** (Zustand): tickers, book, tape, account, connection, UI. Components subscribe with narrow selectors. A ticker for one market re-renders only that market's row, and book updates never re-render the portfolio.
- **Virtualized market list** (`@tanstack/react-virtual`).
- **Chart:** history loads over REST once per market/timeframe, then live trades fold into the current candle client-side with `series.update()`.
- **Connection UX:** connecting / live with RTT / reconnecting / stale. Exponential backoff with jitter, plus app-level ping liveness, because TCP can look open on a dead path.
- **Honest optimistic orders:** a "sending…" row appears instantly. Nothing shows as filled until the durable server result arrives, and the `clientOrderId` makes retries safe.
- **Keyboard-first:** `/` search, `J/K` markets, `Y/N` outcome, `B/S` side, `P/Q` price/quantity, `Enter` submit, `Esc` cancel all in the current market. Clicking a book level loads its price, translated into the ticket's outcome.
- **Tests:** Vitest covers book sequencing (snapshot, delta, stale, gap) and formatting.

## Measured on the running system

| | before | after |
|---|---|---|
| Ingress → applied p50 at ~150 cmd/s (bots only) | 15.5 ms | **4 µs** |
| Ingress → applied p99 | 16.3 ms | **303 µs** |
| Ingress → durable p50 | 15.5 ms | **7 µs** |

**Cause.** At low load the engine threads go idle and park. Windows oversleeps `parkNanos` by its 1–15 ms timer granularity. This is the same root cause found in the load harness, but it shows up in the opposite regime: low load instead of high.

**Fix.** Wake-on-submit. A consumer raises a `parked` flag, re-checks the ring, then parks. Producers unpark it after publishing, with a full fence on each side so a wakeup can't be lost. The 1 ms park timeout is only a safety net. Busy-spin remains available for dedicated cores.

**Crash recovery, observed:** after force-killing the process, restart replayed 20,348 commands in 94 ms, and later 126,638 commands in 172 ms. Markets, accounts and resting orders were intact.
