// Wire types mirroring the gateway. Prices are integer ticks of $0.001 (630 = 63.0¢); cash is in mills.

export type Outcome = 'YES' | 'NO';
export type Action = 'BUY' | 'SELL';
export type Side = 'BID' | 'ASK';
export type Tif = 'GTC' | 'IOC' | 'FOK' | 'POST_ONLY';

export interface Stats {
  marketId: number;
  status: 'OPEN' | 'HALTED' | 'RESOLVED';
  winner: Outcome | null;
  bestBid: number;
  bestAsk: number;
  last: number;
  change24h: number;
  volume: number;
  notional: number;
  trades: number;
  bidDepth: number;
  askDepth: number;
  mark: number;
}

export interface RefQuote {
  bestBid: number;
  bestAsk: number;
  mid: number;
  last: number;
  updatedAtMillis: number;
}

export interface MarketSummary {
  id: number;
  question: string;
  category: string;
  source: 'SYNTHETIC' | 'POLYMARKET';
  slug: string | null;
  closesAt: string | null;
  image: string | null;
  stats: Stats;
  reference: RefQuote | null;
}

export interface TapeEntry {
  tradeId: number;
  price: number;
  quantity: number;
  aggressor: Side;
  ts: number; // epoch micros
}

export interface Candle {
  time: number; // epoch seconds
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface OrderView {
  orderId: number;
  clientOrderId: number;
  marketId: number;
  outcome: Outcome;
  action: Action;
  tif: Tif;
  limitPrice: number;
  quantity: number;
  filled: number;
  filledValue: number;
  status: 'OPEN' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELED';
  reason: string | null;
  createdTs: number;
  updatedTs: number;
}

export interface Fill {
  tradeId: number;
  orderId: number;
  marketId: number;
  outcome: Outcome;
  action: Action;
  price: number;
  quantity: number;
  maker: boolean;
  ts: number;
}

export interface PositionDto {
  marketId: number;
  question: string;
  yes: number;
  no: number;
  yesLocked: number;
  noLocked: number;
  avgYesMilli: number;
  avgNoMilli: number;
  mark: number;
  marketValue: number;
  cost: number;
  unrealizedPnl: number;
  realizedPnl: number;
}

export interface Portfolio {
  accountId: number;
  available: number;
  reserved: number;
  cash: number;
  positionsValue: number;
  equity: number;
  unrealizedPnl: number;
  realizedPnl: number;
  positions: PositionDto[];
  openOrders: OrderView[];
}

export interface OrderResult {
  status: 'OPEN' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELED' | 'PARTIALLY_FILLED_CANCELED' | 'REJECTED';
  orderId: number;
  clientOrderId: number;
  filledQuantity: number;
  remainingQuantity: number;
  avgPriceMilli: number;
  reason: string | null;
  fills: { tradeId: number; price: number; quantity: number }[];
}

export interface SystemStats {
  commandsProcessed: number;
  commandsPerSecond: number;
  durableSequence: number;
  inboundQueueDepth: number;
  outboundQueueDepth: number;
  matchNanos: Hist;
  ingressToAppliedMicros: Hist;
  ingressToDurableMicros: Hist;
  websocketClients: number;
  snapshots?: { mode: 'INLINE' } | { mode: 'FOLLOWER'; followerSequence: number; lastSnapshotSequence: number; lastSnapshotMillis: number; taken: number };
  postgres?: ProjectionStatus;
  clickhouse?: ClickHouseStatus;
  polymarket: {
    enabled: boolean;
    connected: boolean;
    messages: number;
    applied: number;
    stale: number;
    duplicates: number;
    crossedResyncs: number;
    reconnects: number;
    lastMessageAgeMs: number;
    subscribedTokens: number;
  };
}

export interface Hist {
  p50: number;
  p99: number;
  p999: number;
  max: number;
  count: number;
}

// ---------------------------------------------------------------- analytics (phase 9)

export interface Metrics {
  trades: number;
  volume: number;
  buyVolume: number;
  sellVolume: number;
  buyShare: number | null;
  vwap: number | null; // ticks
  open: number | null;
  last: number | null;
  high: number | null;
  low: number | null;
  avgTradeSize: number | null;
  realizedVolCentsPerHour: number | null;
  avgSpread: number | null; // ticks
  avgImbalance: number | null; // -1..1
  refBasisCents: number | null;
  buckets: number;
}

export interface LiveBook {
  bestBid: number | null;
  bestAsk: number | null;
  spread: number | null;
  bidDepth5: number;
  askDepth5: number;
  imbalance: number | null;
  refMid: number | null;
  mark: number;
}

export interface BucketDto {
  t: number; // epoch seconds
  open: number | null;
  high: number | null;
  low: number | null;
  close: number | null;
  volume: number;
  buyVolume: number;
  sellVolume: number;
  trades: number;
  vwap: number | null;
  spread: number | null;
  imbalance: number | null;
  refMid: number | null;
}

export interface MarketAnalytics {
  marketId: number;
  question: string;
  source: string; // clickhouse | memory | memory (clickhouse unavailable)
  windowMinutes: number;
  bucketSeconds: number;
  metrics: Metrics;
  live: LiveBook;
  buckets: BucketDto[];
}

export interface ScannerRow {
  marketId: number;
  question: string;
  category: string;
  marketSource: 'SYNTHETIC' | 'POLYMARKET';
  metrics: Metrics;
  live: LiveBook;
}

export interface LeaderRow {
  rank: number;
  accountId: number;
  bot: boolean;
  realizedPnl: number;
  unrealizedPnl: number;
  totalPnl: number;
  openPositions: number;
}

export interface Leaderboard {
  source: string; // postgres | memory
  rows: LeaderRow[];
}

export interface Page<T> {
  source: string;
  items: T[];
  next: number | null;
}

/** Order row from the Postgres history endpoint. */
export interface OrderRow {
  orderId: number;
  clientOrderId: number;
  marketId: number;
  outcome: Outcome;
  action: Action;
  tif: Tif;
  limitPrice: number;
  quantity: number;
  filled: number;
  avgPriceMilli: number;
  status: OrderView['status'];
  reason: string | null;
  createdTs: number;
  updatedTs: number;
}

export interface ProjectionStatus {
  status: {
    state: 'STARTING' | 'UP' | 'DOWN';
    offset: number;
    queued: number;
    batches: number;
    rows: number;
    lastBatchMicros: number;
    dropped: number;
    gaps: number;
    lastError: string | null;
  };
  lag: number;
}

export interface ClickHouseStatus {
  up: boolean;
  tradesWritten: number;
  samplesWritten: number;
  queued: number;
  dropped: number;
  rejectedRows: number;
  lastFlushMicros: number;
  lastError: string | null;
}
