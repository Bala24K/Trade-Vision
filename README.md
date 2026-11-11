# TradeVision

**A real-time prediction-market exchange and trading terminal: from-scratch matching engine, live Polymarket data, React terminal.**

![TradeVision terminal](docs/terminal.png)

YES/NO binary markets, Polymarket/Kalshi style, traded on a paper venue that implements the real mechanics, not an
animation of prices:
- price-time matching, with cross-outcome minting and burning
- pre-trade risk checks and position accounting
- durability, plus crash recovery from the journal

Half the markets are **live mirrors of Polymarket**. Their order books stream in over WebSocket, and the venue's
market-making bots anchor to them, so paper prices track the real ones.

| | |
|---|---|
| Engine throughput, end to end with journal | **~2.7 M commands/s** |
| Matching cost per command | **p50 203 ns · p99 < 1.5 µs** |
| Ingress → applied on the running server | **p50 4 µs · p99 0.3 ms** |
| Crash recovery (force-kill, restart) | **126,638 commands replayed in 172 ms**, nothing lost |
| Tests | 49 engine (incl. invariant fuzzer + recovery) · 5 gateway (full HTTP flow, feed fixtures) · 6 frontend |
| Dependencies in the engine | **zero** |

## Run it

Requires JDK 21+, Maven, Node 20+.

```bash
cd frontend && npm install && npm run build && cd ..   # builds the UI into the gateway's static resources
mvn clean install                                       # builds everything, runs all tests
java -jar gateway/target/tradevision-gateway-0.1.0-SNAPSHOT.jar
# open http://localhost:8080
```

On first start it creates 8 synthetic markets, discovers 8 live Polymarket markets, and starts the bots. State lives
in `./data` (journal, snapshots, market catalog), so stop it, restart it, or kill it: everything comes back.

- **Frontend dev with hot reload:** keep the jar running, then `cd frontend && npm run dev` → http://localhost:5173.
- **Without network access:** `--tradevision.polymarket.enabled=false` runs synthetic markets only.
- **Docker:** `docker compose up --build`. The Dockerfile and compose file are written but **not yet tested** (Docker was unavailable on the dev machine).

## What's interesting here

**Engine** ([matching-engine.md](docs/matching-engine.md), [benchmarks.md](docs/benchmarks.md))
- **The order book is an array, not a tree.** Prices are probabilities with only 999 ticks, so levels live in a dense array. The next-best price comes from a bitmap scanned with `LZCNT`/`TZCNT`, and cancels are O(1) via intrusive FIFO lists.
- **One book for both outcomes.** BUY NO @ 37¢ is an offer to sell YES @ 63¢. Opposite buyers mint a new contract pair and opposite sellers burn one.
- **Impossible to overdraw by construction.** A fuzzer asserts after every random command that money is conserved and every reservation matches an open order.
- **The journal is the database.** A binary command log uses CRC32C, group commit and torn-tail repair. The journaler is a parallel consumer of the same lock-free ring as the matcher (LMAX-style). Nothing is acknowledged before it's durable.

**Gateway** ([gateway.md](docs/gateway.md))
- **Durability-gated responses:** each HTTP answer is built from exactly the events its command caused, released only once journaled. Idempotent `clientOrderId`. `503` backpressure instead of unbounded queues.
- **WebSocket fan-out:** books are conflated to ≤ 20 Hz with absolute-value deltas, sequence numbers with client-side gap detection, and slow consumers are disconnected.
- **Polymarket ingestion without sequence numbers:** snapshot authority, stale/duplicate dropping, crossed-book detection triggers a resync, plus a watchdog and backoff.

**Terminal**
- Zustand stores are split by update frequency. The market list is virtualized, and the chart updates candles incrementally from the trade stream.
- Connection, stale and RTT states. Optimistic but honest order UX. Keyboard-first trading.

**Performance investigations, each measured before and after:**
1. Disk I/O on the matching thread: **wrong hypothesis**, kept the change anyway.
2. The Windows timer oversleeping `parkNanos` under load: p99 cut 26×.
3. Hash-table rehash pauses: max stall cut 7×.
4. The same timer issue in reverse at *low* load: p50 cut from 15.5 ms to 4 µs with wake-on-submit.

## Layout

```
engine/     zero-dependency core: book, matching, risk, accounts, journal, snapshots, ring buffer, runner
bench/      JMH benchmarks + end-to-end load harness
gateway/    Spring Boot: REST/WS API, read models, durability gate, bots, Polymarket feed
frontend/   React + TypeScript terminal (Vite, Zustand, Lightweight Charts, TanStack Virtual)
docs/       ARCHITECTURE, matching-engine, gateway, benchmarks, ROADMAP, adr/
```

TradeVision is paper trading only and is not an HFT venue. Real-money exchange latency involves far more than a fast in-process matcher.
