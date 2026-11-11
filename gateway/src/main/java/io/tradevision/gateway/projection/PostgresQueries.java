package io.tradevision.gateway.projection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Read queries over the Postgres projection: full, paginated history (the in-memory read model only keeps the
 * most recent few hundred items per account) and the PnL leaderboard.
 *
 * <p>Pagination is <b>keyset</b>, not OFFSET: "give me 50 rows with id below X". OFFSET n makes Postgres produce
 * and discard n rows, so page 1000 costs 1000x page 1. A keyset page is one index range scan whatever its depth.
 */
@Component
@ConditionalOnProperty(name = "tradevision.postgres.enabled", havingValue = "true")
public class PostgresQueries {
    private final JdbcTemplate jdbc;

    public PostgresQueries(DataSource ds) {
        this.jdbc = new JdbcTemplate(ds);
    }

    public record FillRow(long tradeId, long orderId, long marketId, String outcome, String action, int price, long quantity,
                          boolean maker, long ts) {}

    /**
     * Fills for one account, newest first, strictly older than {@code beforeTradeId}.
     * The account can be on either side of a trade, so instead of {@code WHERE maker = ? OR taker = ?} (which
     * defeats an ordered index scan) each side is its own LIMITed index scan and the two are merged.
     */
    public List<FillRow> fills(long account, long beforeTradeId, int limit) {
        return jdbc.query("""
                SELECT * FROM (
                  (SELECT trade_id, maker_order_id AS order_id, market_id, maker_outcome AS outcome,
                          maker_action AS action, price, quantity, true AS maker, ts
                     FROM trades WHERE maker_account_id = ? AND trade_id < ?
                    ORDER BY trade_id DESC LIMIT ?)
                  UNION ALL
                  (SELECT trade_id, taker_order_id, market_id, taker_outcome, taker_action, price, quantity, false, ts
                     FROM trades WHERE taker_account_id = ? AND trade_id < ?
                    ORDER BY trade_id DESC LIMIT ?)
                ) f ORDER BY trade_id DESC LIMIT ?""",
                (rs, i) -> {
                    String outcome = rs.getString("outcome");
                    int yes = rs.getInt("price");
                    return new FillRow(rs.getLong("trade_id"), rs.getLong("order_id"), rs.getLong("market_id"), outcome,
                            rs.getString("action"), "YES".equals(outcome) ? yes : 1000 - yes, rs.getLong("quantity"),
                            rs.getBoolean("maker"), micros(rs.getTimestamp("ts")));
                },
                account, beforeTradeId, limit, account, beforeTradeId, limit, limit);
    }

    public record OrderRow(long orderId, long clientOrderId, long marketId, String outcome, String action, String tif,
                           int limitPrice, long quantity, long filled, long avgPriceMilli, String status, String reason,
                           long createdTs, long updatedTs) {}

    public List<OrderRow> orders(long account, long beforeOrderId, int limit) {
        return jdbc.query("""
                SELECT order_id, client_order_id, market_id, outcome, action, tif, limit_price, quantity, filled,
                       filled_value, status, cancel_reason, created_at, updated_at
                  FROM orders WHERE account_id = ? AND order_id < ?
                 ORDER BY order_id DESC LIMIT ?""",
                (rs, i) -> {
                    long filled = rs.getLong("filled");
                    String status = rs.getString("status");
                    if ("OPEN".equals(status) && filled > 0) status = "PARTIALLY_FILLED";
                    return new OrderRow(rs.getLong("order_id"), rs.getLong("client_order_id"), rs.getLong("market_id"),
                            rs.getString("outcome"), rs.getString("action"), rs.getString("tif"), rs.getInt("limit_price"),
                            rs.getLong("quantity"), filled, filled == 0 ? 0 : rs.getLong("filled_value") * 1000 / filled,
                            status, rs.getString("cancel_reason"), micros(rs.getTimestamp("created_at")),
                            micros(rs.getTimestamp("updated_at")));
                },
                account, beforeOrderId, limit);
    }

    public record LeaderRow(long accountId, boolean bot, long realizedPnl, long unrealizedPnl, long totalPnl,
                            int openPositions, int marketsTraded) {}

    /**
     * Total PnL = realized + mark-to-market of open positions, marking each market at its last trade
     * (resolved markets have been settled into realized PnL already). Same formula as the portfolio view.
     */
    public List<LeaderRow> leaderboard(int limit, boolean includeBots) {
        return jdbc.query("""
                SELECT p.account_id, a.is_bot,
                       SUM(p.realized_pnl) AS realized,
                       SUM(CASE WHEN p.yes = 0 AND p.no = 0 THEN 0
                                ELSE p.yes * mk.mark + p.no * (1000 - mk.mark) - p.yes_cost - p.no_cost END) AS unrealized,
                       COUNT(*) FILTER (WHERE p.yes <> 0 OR p.no <> 0) AS open_positions,
                       COUNT(*) AS markets_traded
                  FROM positions p
                  JOIN accounts a ON a.account_id = p.account_id
                  JOIN LATERAL (SELECT CASE WHEN m.status = 'RESOLVED' THEN 0 ELSE COALESCE(m.last_price, 500) END AS mark
                                  FROM markets m WHERE m.market_id = p.market_id) mk ON true
                 WHERE ? OR NOT a.is_bot
                 GROUP BY p.account_id, a.is_bot
                 ORDER BY SUM(p.realized_pnl) + SUM(CASE WHEN p.yes = 0 AND p.no = 0 THEN 0
                          ELSE p.yes * mk.mark + p.no * (1000 - mk.mark) - p.yes_cost - p.no_cost END) DESC
                 LIMIT ?""",
                (rs, i) -> {
                    long r = rs.getLong("realized"), u = rs.getLong("unrealized");
                    return new LeaderRow(rs.getLong("account_id"), rs.getBoolean("is_bot"), r, u, r + u,
                            rs.getInt("open_positions"), rs.getInt("markets_traded"));
                },
                includeBots, limit);
    }

    /** Row counts per table, for the system panel. Uses planner estimates: exact COUNT(*) on big tables is a full scan. */
    public Map<String, Long> tableSizes() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        jdbc.query("""
                SELECT relname, GREATEST(reltuples, 0)::bigint AS n FROM pg_class
                 WHERE relname IN ('trades', 'orders', 'positions', 'accounts', 'markets') ORDER BY relname""",
                rs -> { out.put(rs.getString(1), rs.getLong(2)); });
        return out;
    }

    private static long micros(java.sql.Timestamp t) {
        return t.getTime() * 1000 + (t.getNanos() / 1000) % 1000;
    }
}
