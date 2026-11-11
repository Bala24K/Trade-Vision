package io.tradevision.gateway.api;

import io.tradevision.engine.Action;
import io.tradevision.engine.Outcome;
import io.tradevision.engine.Prices;
import io.tradevision.engine.TimeInForce;
import io.tradevision.gateway.engine.EngineHost;
import io.tradevision.gateway.engine.Event;
import io.tradevision.gateway.read.AccountState;
import io.tradevision.gateway.read.ReadModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Turns API requests into engine commands and engine events back into a single, honest answer.
 *
 * <p><b>Idempotency:</b> clients send a {@code clientOrderId}. A retry with the same id (e.g. after a network
 * timeout) returns the original result instead of placing a second order. The cache holds each request's
 * <i>outcome</i> future, which never times out; callers get a time-limited view of it. A key is removed only
 * when the engine proves the command was never submitted (ring full). After a caller timeout the key stays, so a
 * retry waits for, or immediately returns, the original order's result (audit C2). The last 50k keys are kept in
 * memory; they do not survive a restart (audit C3, not addressed here).
 */
@Service
public class OrderService {
    public record PlaceOrder(long marketId, String outcome, String side, String type, Integer price, long quantity,
                             String tif, Long clientOrderId) {}

    public record FillDto(long tradeId, int price, long quantity) {}

    public record OrderResult(String status, long orderId, long clientOrderId, long filledQuantity, long remainingQuantity,
                              long avgPriceMilli, String reason, List<FillDto> fills) {}

    private final EngineHost engine;
    private final ReadModel readModel;
    private final long timeoutMillis;
    private final Map<String, CompletableFuture<OrderResult>> recent = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CompletableFuture<OrderResult>> e) {
            return size() > 50_000;
        }
    };

    public OrderService(EngineHost engine, ReadModel readModel,
                        @Value("${tradevision.request-timeout-ms:5000}") long timeoutMillis) {
        this.engine = engine;
        this.readModel = readModel;
        this.timeoutMillis = timeoutMillis;
    }

    public CompletableFuture<OrderResult> place(long accountId, PlaceOrder req) {
        Outcome outcome = parse(Outcome.class, req.outcome(), "outcome");
        Action action = parse(Action.class, req.side(), "side");
        if (req.type() != null && !"MARKET".equalsIgnoreCase(req.type()) && !"LIMIT".equalsIgnoreCase(req.type())) {
            throw new ValidationException("INVALID_TYPE", "type", "type must be LIMIT or MARKET");
        }
        boolean market = "MARKET".equalsIgnoreCase(req.type());
        TimeInForce tif = market ? TimeInForce.IOC
                : req.tif() == null ? TimeInForce.GTC : parse(TimeInForce.class, req.tif(), "tif");
        int price;
        if (market) {
            price = action == Action.BUY ? Prices.MAX_PRICE : Prices.MIN_PRICE; // sweep; buys refund the unused reservation
        } else {
            if (req.price() == null) throw new ValidationException("INVALID_PRICE", "price", "price is required for limit orders");
            if (req.price() < Prices.MIN_PRICE || req.price() > Prices.MAX_PRICE) {
                throw new ValidationException("INVALID_PRICE", "price",
                        "price must be between " + Prices.MIN_PRICE + " and " + Prices.MAX_PRICE + " ticks ($0.001 each)");
            }
            price = req.price();
        }
        if (req.quantity() < 1 || req.quantity() > Prices.MAX_QUANTITY) {
            throw new ValidationException("INVALID_QUANTITY", "quantity", "quantity must be between 1 and " + Prices.MAX_QUANTITY);
        }
        long coid = req.clientOrderId() != null ? req.clientOrderId() : System.nanoTime();
        String key = accountId + ":" + coid;

        CompletableFuture<OrderResult> result;
        boolean fresh;
        synchronized (recent) {
            CompletableFuture<OrderResult> existing = recent.get(key);
            fresh = existing == null;
            result = fresh
                    ? engine.submitTracked(c -> c.placeOrder(accountId, coid, req.marketId(), outcome, action, tif, price, req.quantity()))
                            .thenApply(events -> summarize(events, coid, req.quantity()))
                    : existing;
            if (fresh) recent.put(key, result);
        }
        if (fresh) {
            final CompletableFuture<OrderResult> mine = result;
            mine.whenComplete((r, e) -> {
                // Forget the key only if the command provably never entered the engine. A caller timeout is not
                // that: the order still executes, so a retry must find this same outcome.
                if (e != null && neverSubmitted(e)) synchronized (recent) { recent.remove(key, mine); }
            });
        }
        return EngineHost.withTimeout(result, timeoutMillis);
    }

    static OrderResult summarize(List<Event> events, long coid, long quantity) {
        long orderId = -1, filled = 0, value = 0;
        String status = null, reason = null;
        List<FillDto> fills = new ArrayList<>();
        Outcome outcome = null;
        for (Event ev : events) {
            if (ev instanceof Event.OrderRejected r) {
                status = "REJECTED";
                reason = r.reason().name();
            } else if (ev instanceof Event.OrderAccepted a) {
                orderId = a.orderId();
                outcome = a.outcome();
            } else if (ev instanceof Event.Trade t && t.takerOrderId() == orderId) {
                int px = outcome == Outcome.YES ? t.price() : Prices.complement(t.price());
                fills.add(new FillDto(t.tradeId(), px, t.quantity()));
                filled += t.quantity();
                value += (long) px * t.quantity();
            } else if (ev instanceof Event.OrderClosed c && c.orderId() == orderId) {
                status = c.filled() ? "FILLED" : (filled > 0 ? "PARTIALLY_FILLED_CANCELED" : "CANCELED");
                if (!c.filled() && c.reason() != null) reason = c.reason().name();
            }
        }
        if (status == null) status = filled > 0 ? "PARTIALLY_FILLED" : "OPEN";
        long avg = filled == 0 ? 0 : value * 1000 / filled;
        return new OrderResult(status, orderId, coid, filled, "REJECTED".equals(status) ? 0 : quantity - filled, avg, reason, fills);
    }

    private static boolean neverSubmitted(Throwable e) {
        Throwable c = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
        return c instanceof EngineHost.OverloadedException;
    }

    public CompletableFuture<String> cancel(long accountId, long orderId) {
        return engine.submit(c -> c.cancelOrder(accountId, orderId)).thenApply(events -> {
            for (Event ev : events) {
                if (ev instanceof Event.CancelRejected r) return r.reason().name();
            }
            return null;
        });
    }

    /**
     * Cancel by the client's own order id. Clients know this id the moment they send an order, before the
     * exchange has assigned an order id, so a "cancel" can race the "place". If that place is still in flight
     * we wait for its (durable) result and then cancel the engine order it created: the cancel is never lost and
     * never hits the wrong order.
     */
    public CompletableFuture<String> cancelByClientId(long accountId, long clientOrderId) {
        CompletableFuture<OrderResult> inflight;
        synchronized (recent) {
            inflight = recent.get(accountId + ":" + clientOrderId);
        }
        CompletableFuture<Long> orderId;
        if (inflight != null) {
            orderId = inflight.thenApply(OrderResult::orderId);
        } else {
            AccountState a = readModel.account(accountId);
            orderId = CompletableFuture.completedFuture(a == null ? -1 : a.openOrderIdByClientId(clientOrderId));
        }
        return EngineHost.withTimeout(
                orderId.thenCompose(id -> id < 0 ? CompletableFuture.completedFuture("UNKNOWN_ORDER") : cancel(accountId, id)),
                timeoutMillis);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        String code = "INVALID_" + field.toUpperCase();
        if (value == null) throw new ValidationException(code, field, field + " is required");
        try {
            return Enum.valueOf(type, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(code, field, "invalid " + field + ": " + value);
        }
    }
}
