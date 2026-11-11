# Matching engine & accounting model

## Units
- **Price** = integer ticks of $0.001 (`630` = 63.0¢). Valid limits `1..999`; a binary contract at 0 or $1 is already decided.
- **Cash** = integer mills ($0.001). `price × quantity` is always an exact integer. No floating point touches money.

## One unified book per market
A YES contract at `p` and a NO contract at `1000 − p` are the same bet from opposite sides. So each market has **one**
book quoted in YES prices, and the four user intents map onto its two sides:

| Intent | Book side | Book price |
|---|---|---|
| BUY YES @ p | BID | p |
| SELL YES @ p | ASK | p |
| BUY NO @ q | ASK | 1000 − q |
| SELL NO @ q | BID | 1000 − q |

This makes cross-outcome matching automatic. Depending on who meets whom, a trade does one of three things:

| Maker × Taker | Effect | Open interest |
|---|---|---|
| BUY YES × SELL YES (or BUY NO × SELL NO) | ordinary transfer | unchanged |
| BUY YES × BUY NO | **mint** a new YES/NO pair; both pay, $1 total | +q |
| SELL YES × SELL NO | **burn** a pair; both receive, $1 total | −q |

## Matching rules
- **Price-time priority.** Best price first; FIFO within a price level.
- **Trades execute at the maker's price.** The taker keeps any price improvement, refunded from its reservation.
- **Self-trade prevention: cancel resting.** If the incoming order would hit the same account's resting order, the resting order is cancelled and matching continues.
- **Time in force:** `GTC` (rest the remainder), `IOC` (cancel the remainder; a market order is IOC at 999), `FOK` (all-or-nothing, checked before touching the book, excluding own orders), `POST_ONLY` (reject if it would cross).

## Pre-trade risk: impossible to overdraw by construction
- **BUY** reserves `quantity × limit` up front. Each fill releases `fill × limit` and spends `fill × price`, so a fill can never cost more than was reserved.
- **SELL** locks the units being sold. You cannot sell inventory you don't hold or have already committed. To bet against an outcome you BUY the other side; there is no naked shorting.
- Halted markets reject new orders but still accept cancels. Resolved markets reject everything.

## Positions & PnL
- YES and NO inventory are held separately, with cost basis stored as **total cost** (exact) rather than average price.
- **Auto-merge:** one YES plus one NO always pays exactly $1. Whenever an account holds *unlocked* units of both, they merge into cash and the realized PnL is booked. This is also how "BUY NO while long YES" closes a position.
- **Realized PnL** accrues on sells, merges and settlement. **Unrealized PnL** is never stored. It is `marketValue(mark) − cost`, computed by readers, because it changes on every tick even when the account does nothing.
- **Mark price** is the mid if both sides are quoted, else the last trade, else 50¢.
- **Resolution:** cancel every resting order (releasing reservations and locks), pay $1 per winning contract, zero all positions, and settle accounts in id order so the event stream is deterministic.

## Invariants (checked after every command in the fuzzer)
1. `Σ cash + $1 × Σ openInterest == deposits − withdrawals`, i.e. money is conserved.
2. Per market: Σ YES held == Σ NO held == open interest.
3. Each account's `reserved` equals the reserved cash of its resting BUYs. Each position's locks equal the remaining quantity of its resting SELLs.
4. No negative balances, holdings or locks. No unmerged free pairs. No cost basis on an empty position.
5. No book is ever crossed. Level totals equal the sum of their orders. The id index and the books agree exactly.

## Data structures
| Concern | Choice | Why |
|---|---|---|
| Price levels | `PriceLevel[1000]` per side, indexed by price | bounded domain: O(1) lookup, no tree rebalancing |
| Best price after a level empties | 1024-bit `PriceBitSet` + `LZCNT`/`TZCNT` | ≤ 16 word reads, usually one |
| Time priority | intrusive doubly-linked list in each level | O(1) cancel from anywhere in the queue |
| Order-id index | `LongObjectMap` (open addressing, backward-shift delete) | no boxing, cache-friendly probes, no tombstones under cancel-heavy flow |
| Order objects | `OrderPool` free list | zero allocation in steady state |
| Events | listener callbacks over live objects | no event-object allocation; the consumer copies into its own ring slots |
