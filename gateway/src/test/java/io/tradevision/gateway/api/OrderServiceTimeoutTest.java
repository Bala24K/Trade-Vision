package io.tradevision.gateway.api;

import io.tradevision.engine.Action;
import io.tradevision.engine.Command;
import io.tradevision.engine.Outcome;
import io.tradevision.engine.TimeInForce;
import io.tradevision.gateway.engine.EngineHost;
import io.tradevision.gateway.engine.Event;
import io.tradevision.gateway.read.ReadModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Audit C2. A stub engine host gives the test full control over when (or whether) a submitted command's outcome
 * arrives, which forces a caller timeout deterministically. The real {@link EngineHost#withTimeout} and
 * {@link OrderService} idempotency logic are under test.
 */
class OrderServiceTimeoutTest {
    static final long TIMEOUT_MS = 50;

    /** Records every command that reaches the "engine"; outcomes complete only when the test says so. */
    static final class StubEngine extends EngineHost {
        final List<Command> submitted = new ArrayList<>();
        final List<CompletableFuture<List<Event>>> outcomes = new ArrayList<>();
        boolean ringFull;

        StubEngine() {
            super(null, new ReadModel());
        }

        @Override
        public synchronized CompletableFuture<List<Event>> submitTracked(Consumer<Command> fill) {
            if (ringFull) return CompletableFuture.failedFuture(new OverloadedException()); // never submitted
            Command c = new Command();
            fill.accept(c);
            submitted.add(c);
            var f = new CompletableFuture<List<Event>>();
            outcomes.add(f);
            return f;
        }
    }

    static final OrderService.PlaceOrder REQ = new OrderService.PlaceOrder(1, "YES", "BUY", "LIMIT", 500, 10, null, 42L);

    static Throwable failureOf(CompletableFuture<?> f) {
        CompletionException e = assertThrows(CompletionException.class, f::join);
        return e.getCause();
    }

    static List<Event> accepted(long orderId) {
        return List.of(new Event.OrderAccepted(orderId, 42, 7, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, 500, 10, 1));
    }

    @Test
    void timeoutDoesNotForgetTheOrder_retryReturnsTheOriginalResultWithoutResubmitting() throws Exception {
        var engine = new StubEngine();
        var orders = new OrderService(engine, new ReadModel(), TIMEOUT_MS);

        // 1. The command is submitted, but its outcome is not durable before the caller's deadline.
        var first = orders.place(7, REQ);
        assertInstanceOf(EngineHost.OutcomePendingException.class, failureOf(first));
        assertEquals(1, engine.submitted.size());
        assertFalse(engine.outcomes.get(0).isDone(), "a caller timeout must not cancel or fail the engine outcome");

        // 2. Retry while still pending: same outcome, no second command, still pending for this caller too.
        var retry = orders.place(7, REQ);
        assertInstanceOf(EngineHost.OutcomePendingException.class, failureOf(retry));
        assertEquals(1, engine.submitted.size(), "retry with the same clientOrderId must not place another order");

        // 3. The original command completes. The next retry returns its real result, still without resubmitting.
        engine.outcomes.get(0).complete(accepted(901));
        var late = orders.place(7, REQ).get(1, TimeUnit.SECONDS);
        assertEquals(901, late.orderId());
        assertEquals("OPEN", late.status());
        assertEquals(1, engine.submitted.size());
    }

    @Test
    void retryAfterTimeoutWaitsForTheInFlightOutcome() throws Exception {
        var engine = new StubEngine();
        var orders = new OrderService(engine, new ReadModel(), 2_000);
        var first = orders.place(7, REQ);
        var retry = orders.place(7, REQ); // arrives while the first is in flight
        engine.outcomes.get(0).complete(accepted(55));
        assertEquals(55, first.get(1, TimeUnit.SECONDS).orderId());
        assertEquals(55, retry.get(1, TimeUnit.SECONDS).orderId());
        assertEquals(1, engine.submitted.size());
    }

    @Test
    void neverSubmittedCommandsAreForgotten_soARetryIsAllowed() throws Exception {
        var engine = new StubEngine();
        var orders = new OrderService(engine, new ReadModel(), TIMEOUT_MS);

        engine.ringFull = true; // backpressure: the engine provably did not receive the command
        assertInstanceOf(EngineHost.OverloadedException.class, failureOf(orders.place(7, REQ)));
        assertEquals(0, engine.submitted.size());

        engine.ringFull = false;
        var retry = orders.place(7, REQ);
        assertEquals(1, engine.submitted.size(), "a never-submitted order may be retried");
        engine.outcomes.get(0).complete(accepted(77));
        assertEquals(77, retry.get(1, TimeUnit.SECONDS).orderId());
    }

    @Test
    void differentClientOrderIdsAreIndependent() {
        var engine = new StubEngine();
        var orders = new OrderService(engine, new ReadModel(), TIMEOUT_MS);
        orders.place(7, REQ);
        orders.place(7, new OrderService.PlaceOrder(1, "YES", "BUY", "LIMIT", 500, 10, null, 43L));
        orders.place(8, REQ); // same client id, different account
        assertEquals(3, engine.submitted.size());
    }

    @Test
    void withTimeoutLeavesTheUnderlyingFutureIntact() throws Exception {
        var outcome = new CompletableFuture<String>();
        var view = EngineHost.withTimeout(outcome, 20);
        assertInstanceOf(EngineHost.OutcomePendingException.class, failureOf(view));
        assertFalse(outcome.isDone());
        outcome.complete("done");
        assertEquals("done", EngineHost.withTimeout(outcome, 20).get(1, TimeUnit.SECONDS));
    }
}
