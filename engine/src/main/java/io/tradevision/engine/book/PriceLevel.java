package io.tradevision.engine.book;

/** All resting orders at one price on one side, in time priority (head = oldest = first to fill). */
public final class PriceLevel {
    final int price;
    Order head;
    Order tail;
    long totalQuantity;
    int orderCount;

    PriceLevel(int price) {
        this.price = price;
    }

    public int price() {
        return price;
    }

    public Order head() {
        return head;
    }

    public long totalQuantity() {
        return totalQuantity;
    }

    public int orderCount() {
        return orderCount;
    }

    public boolean isEmpty() {
        return head == null;
    }

    void append(Order o) {
        o.level = this;
        o.prev = tail;
        o.next = null;
        if (tail == null) head = o;
        else tail.next = o;
        tail = o;
        totalQuantity += o.remaining;
        orderCount++;
    }

    void unlink(Order o) {
        if (o.prev == null) head = o.next;
        else o.prev.next = o.next;
        if (o.next == null) tail = o.prev;
        else o.next.prev = o.prev;
        totalQuantity -= o.remaining;
        orderCount--;
        o.prev = o.next = null;
        o.level = null;
    }
}
