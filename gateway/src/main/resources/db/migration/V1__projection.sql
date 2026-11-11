-- TradeVision relational projection.
--
-- This schema is NOT the source of truth: the engine's command journal is. Every row here is derived from
-- durable engine events and can be rebuilt by replaying the journal. Units match the engine:
--   prices  = YES ticks of $0.001 (1..999); a NO price is 1000 - YES price
--   money   = mills ($0.001); quantities = contracts.
-- No foreign keys on purpose: the projector writes in event order inside one transaction per batch, and FK
-- checks would only add write cost to a table set whose integrity is guaranteed upstream.

CREATE TABLE projection_offset (
    name        TEXT PRIMARY KEY,
    seq         BIGINT      NOT NULL,           -- last journal sequence fully applied (exactly-once marker)
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Sequence ranges the projection never saw (e.g. Postgres was down longer than the in-memory buffer and the
-- journal had since been compacted). Recorded, never hidden.
CREATE TABLE projection_gaps (
    from_seq    BIGINT      NOT NULL,
    to_seq      BIGINT      NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (from_seq, to_seq)
);

CREATE TABLE markets (
    market_id   BIGINT PRIMARY KEY,
    question    TEXT,
    source      TEXT,
    status      TEXT        NOT NULL DEFAULT 'OPEN',
    winner      TEXT,
    last_price  INT,                             -- mark for PnL queries (last trade, YES ticks)
    volume      BIGINT      NOT NULL DEFAULT 0,  -- contracts
    notional    BIGINT      NOT NULL DEFAULT 0,  -- mills
    trade_count BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE accounts (
    account_id  BIGINT PRIMARY KEY,
    available   BIGINT      NOT NULL DEFAULT 0,
    reserved    BIGINT      NOT NULL DEFAULT 0,
    is_bot      BOOLEAN     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE orders (
    order_id        BIGINT PRIMARY KEY,
    client_order_id BIGINT      NOT NULL,
    account_id      BIGINT      NOT NULL,
    market_id       BIGINT      NOT NULL,
    outcome         TEXT        NOT NULL,        -- YES | NO
    action          TEXT        NOT NULL,        -- BUY | SELL
    tif             TEXT        NOT NULL,
    limit_price     INT         NOT NULL,        -- in the order's own outcome ticks
    quantity        BIGINT      NOT NULL,
    filled          BIGINT      NOT NULL DEFAULT 0,
    filled_value    BIGINT      NOT NULL DEFAULT 0, -- sum(price * qty) in own-outcome ticks -> avg fill price
    status          TEXT        NOT NULL DEFAULT 'OPEN', -- OPEN | FILLED | CANCELED (partial = OPEN and filled > 0)
    cancel_reason   TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);
-- "My orders, newest first", keyset-paginated by order_id.
CREATE INDEX orders_account_idx ON orders (account_id, order_id DESC);
-- "My open orders" stays small even when history is huge.
CREATE INDEX orders_open_idx ON orders (account_id) WHERE status = 'OPEN';

CREATE TABLE trades (
    trade_id         BIGINT PRIMARY KEY,
    seq              BIGINT      NOT NULL,       -- journal sequence of the command that caused it
    market_id        BIGINT      NOT NULL,
    price            INT         NOT NULL,       -- YES ticks
    quantity         BIGINT      NOT NULL,
    aggressor        TEXT        NOT NULL,       -- BID = taker bought YES (or sold NO)
    maker_order_id   BIGINT      NOT NULL,
    maker_account_id BIGINT      NOT NULL,
    maker_outcome    TEXT        NOT NULL,
    maker_action     TEXT        NOT NULL,
    taker_order_id   BIGINT      NOT NULL,
    taker_account_id BIGINT      NOT NULL,
    taker_outcome    TEXT        NOT NULL,
    taker_action     TEXT        NOT NULL,
    ts               TIMESTAMPTZ NOT NULL
);
-- A fill belongs to two accounts. Two narrow indexes + a UNION ALL query beat one OR condition: each branch
-- is an ordered index scan that stops after LIMIT rows (see PostgresQueries.fills).
CREATE INDEX trades_maker_idx ON trades (maker_account_id, trade_id DESC);
CREATE INDEX trades_taker_idx ON trades (taker_account_id, trade_id DESC);
CREATE INDEX trades_market_idx ON trades (market_id, trade_id DESC);
-- Trades arrive in time order, so a BRIN index on ts is tiny (a few KB per GB) yet prunes time-range scans.
CREATE INDEX trades_ts_brin ON trades USING brin (ts);

CREATE TABLE positions (
    account_id   BIGINT      NOT NULL,
    market_id    BIGINT      NOT NULL,
    yes          BIGINT      NOT NULL,
    no           BIGINT      NOT NULL,
    yes_locked   BIGINT      NOT NULL,
    no_locked    BIGINT      NOT NULL,
    yes_cost     BIGINT      NOT NULL,
    no_cost      BIGINT      NOT NULL,
    realized_pnl BIGINT      NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, market_id)
);
CREATE INDEX positions_market_idx ON positions (market_id);

CREATE TABLE settlements (
    seq        BIGINT      NOT NULL,
    account_id BIGINT      NOT NULL,
    market_id  BIGINT      NOT NULL,
    payout     BIGINT      NOT NULL,
    ts         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (seq, account_id, market_id)
);

CREATE TABLE order_rejections (
    seq             BIGINT      NOT NULL,
    account_id      BIGINT      NOT NULL,
    client_order_id BIGINT      NOT NULL,
    market_id       BIGINT      NOT NULL,
    reason          TEXT        NOT NULL,
    ts              TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (seq, account_id, client_order_id)
);
CREATE INDEX order_rejections_reason_idx ON order_rejections (reason, ts);
