package io.tradevision.engine.book;

/**
 * Free-list of {@link Order} objects. The engine is single-threaded, so this needs no synchronization.
 * Bounded: once {@code maxRetained} orders are pooled, further released orders are left to the GC.
 */
public final class OrderPool {
    private final Order[] free;
    private int size;

    public OrderPool(int maxRetained) {
        free = new Order[maxRetained];
    }

    public Order acquire() {
        return size > 0 ? free[--size] : new Order();
    }

    public void release(Order o) {
        o.reset();
        if (size < free.length) free[size++] = o;
    }

    public int available() {
        return size;
    }
}
