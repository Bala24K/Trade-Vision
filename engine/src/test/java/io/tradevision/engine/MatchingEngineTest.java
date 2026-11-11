package io.tradevision.engine;

import io.tradevision.engine.account.Account;
import io.tradevision.engine.account.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.tradevision.engine.Action.BUY;
import static io.tradevision.engine.Action.SELL;
import static io.tradevision.engine.Outcome.NO;
import static io.tradevision.engine.Outcome.YES;
import static io.tradevision.engine.TimeInForce.FOK;
import static io.tradevision.engine.TimeInForce.GTC;
import static io.tradevision.engine.TimeInForce.IOC;
import static io.tradevision.engine.TimeInForce.POST_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingEngineTest {
    static final long M = 1;
    static final long ALICE = 1, BOB = 2, CAROL = 3;
    static final long DOLLARS_1000 = 1_000_000;

    RecordingListener events;
    MatchingEngine engine;
    long ts;
    long coid;

    @BeforeEach
    void setUp() {
        events = new RecordingListener();
        engine = new MatchingEngine(events);
        engine.createMarket(M, ++ts);
        for (long a : new long[] {ALICE, BOB, CAROL}) {
            engine.createAccount(a, ++ts);
            engine.deposit(a, DOLLARS_1000, ++ts);
        }
    }

    @AfterEach
    void invariantsHold() {
        EngineInvariants.assertValid(engine);
    }

    long place(long acct, Outcome o, Action a, int price, long qty) {
        return place(acct, o, a, GTC, price, qty);
    }

    long place(long acct, Outcome o, Action a, TimeInForce tif, int price, long qty) {
        return engine.placeOrder(acct, ++coid, M, o, a, tif, price, qty, ++ts);
    }

    Account acct(long id) {
        return engine.account(id);
    }

    Position pos(long id) {
        return engine.account(id).position(M);
    }

    /** Give {@code acct} {@code qty} YES at 50.0¢ by trading against CAROL, who ends up holding the NO side. */
    void seedYes(long acct, long qty) {
        place(CAROL, NO, BUY, 500, qty);
        place(acct, YES, BUY, 500, qty);
        events.clear();
    }

    @Nested
    class BookBasics {
        @Test
        void restingOrdersFormTheBook() {
            seedYes(BOB, 300);
            place(ALICE, YES, BUY, 620, 240);
            place(ALICE, YES, BUY, 630, 120);
            place(BOB, YES, SELL, 640, 0); // invalid qty, rejected, not resting
            place(BOB, YES, SELL, 650, 150);
            place(BOB, YES, SELL, 640, 90);

            var book = engine.market(M).book();
            assertEquals(630, book.bestBid());
            assertEquals(640, book.bestAsk());
            assertEquals(120, book.quantityAt(Side.BID, 630));
            assertEquals(90, book.quantityAt(Side.ASK, 640));
        }

        @Test
        void buyNoRestsOnTheAskSideAtTheComplementPrice() {
            place(ALICE, NO, BUY, 370, 100);
            var book = engine.market(M).book();
            assertEquals(630, book.bestAsk(), "BUY NO @37.0c is an offer to sell YES @63.0c");
            assertEquals(-1, book.bestBid());
        }
    }

    @Nested
    class Matching {
        @Test
        void partialFillAtMakerPriceWithPriceImprovementRefund() {
            // Proposal example: resting sell 100 @ 64c, incoming buy 60 @ 65c -> 60 @ 64c, 40 left.
            seedYes(BOB, 100);
            long sell = place(BOB, YES, SELL, 640, 100);
            long aliceBefore = acct(ALICE).available();
            long buy = place(ALICE, YES, BUY, 650, 60);

            assertEquals(1, events.trades.size());
            var t = events.trades.get(0);
            assertEquals(640, t.price(), "executes at the resting (maker) price");
            assertEquals(60, t.qty());
            assertEquals(sell, t.makerOrder());
            assertEquals(buy, t.takerOrder());
            assertEquals(40, engine.order(sell).remaining);
            assertNull(engine.order(buy), "fully filled taker does not rest");
            assertEquals(aliceBefore - 60 * 640, acct(ALICE).available(), "pays 64c, not the 65c limit");
            assertEquals(0, acct(ALICE).reserved());
        }

        @Test
        void priceTimePriority() {
            seedYes(BOB, 100);
            seedYes(ALICE, 100);
            long first = place(BOB, YES, SELL, 640, 50);
            long second = place(ALICE, YES, SELL, 640, 50);
            long better = place(ALICE, YES, SELL, 630, 10);

            place(CAROL, YES, BUY, 640, 30);
            assertEquals(2, events.trades.size());
            assertEquals(better, events.trades.get(0).makerOrder(), "best price first");
            assertEquals(first, events.trades.get(1).makerOrder(), "then oldest at the price");
            assertEquals(20, events.trades.get(1).qty());
            assertEquals(30, engine.order(first).remaining);
            assertEquals(50, engine.order(second).remaining);
        }

        @Test
        void sweepsMultipleLevelsAndRestsRemainder() {
            seedYes(BOB, 440);
            place(BOB, YES, SELL, 640, 90);
            place(BOB, YES, SELL, 650, 150);
            place(BOB, YES, SELL, 660, 200);
            long buy = place(ALICE, YES, BUY, 650, 300);

            assertEquals(2, events.trades.size());
            assertEquals(240, events.trades.stream().mapToLong(RecordingListener.Trade::qty).sum());
            var book = engine.market(M).book();
            assertEquals(650, book.bestBid(), "60 unfilled rest at the limit");
            assertEquals(60, engine.order(buy).remaining);
            assertEquals(660, book.bestAsk());
        }

        @Test
        void yesBuyerAndNoBuyerMintANewPair() {
            place(ALICE, YES, BUY, 630, 100);
            place(BOB, NO, BUY, 370, 100);

            assertEquals(1, events.trades.size());
            assertEquals(630, events.trades.get(0).price());
            assertEquals(100, pos(ALICE).yes());
            assertEquals(63_000, pos(ALICE).yesCost());
            assertEquals(100, pos(BOB).no());
            assertEquals(37_000, pos(BOB).noCost(), "NO buyer pays the complement");
            assertEquals(100, engine.market(M).openInterest(), "100 new contracts exist");
        }

        @Test
        void noBuyerGetsPriceImprovementInNoTerms() {
            place(ALICE, YES, BUY, 640, 100);       // bid 64.0c YES
            place(BOB, NO, BUY, 400, 100);          // willing to pay 40.0c NO, i.e. ask 60.0c YES
            assertEquals(640, events.trades.get(0).price());
            assertEquals(36_000, pos(BOB).noCost(), "fills at 36.0c NO, better than the 40.0c limit");
            assertEquals(DOLLARS_1000 - 36_000, acct(BOB).available());
        }

        @Test
        void yesSellerAndNoSellerBurnPairs() {
            seedYes(ALICE, 100);            // CAROL holds 100 NO
            place(ALICE, YES, SELL, 550, 100);
            place(CAROL, NO, SELL, 450, 100); // SELL NO @45c == bid YES @55c
            assertEquals(1, events.trades.size());
            assertEquals(0, engine.market(M).openInterest());
            assertEquals(55_000 - 50_000, pos(ALICE).realizedPnl());
            assertEquals(45_000 - 50_000, pos(CAROL).realizedPnl());
        }
    }

    @Nested
    class Risk {
        @Test
        void buyReservesWorstCaseAndCancelReleasesIt() {
            long id = place(ALICE, YES, BUY, 630, 1000);
            assertEquals(630_000, acct(ALICE).reserved());
            assertEquals(DOLLARS_1000 - 630_000, acct(ALICE).available());
            assertTrue(engine.cancelOrder(ALICE, id, ++ts));
            assertEquals(0, acct(ALICE).reserved());
            assertEquals(DOLLARS_1000, acct(ALICE).available());
        }

        @Test
        void insufficientFundsIsRejectedWithoutSideEffects() {
            long id = place(ALICE, YES, BUY, 999, 1002); // $1000.998 > $1000
            assertEquals(-1, id);
            assertEquals(RejectReason.INSUFFICIENT_FUNDS, events.rejects.get(0).reason());
            assertEquals(DOLLARS_1000, acct(ALICE).available());
            assertEquals(0, engine.market(M).book().restingOrders());
        }

        @Test
        void cannotSellWhatYouDoNotOwnOrHaveAlreadyCommitted() {
            assertEquals(-1, place(ALICE, YES, SELL, 600, 1));
            seedYes(ALICE, 100);
            place(ALICE, YES, SELL, 700, 80);
            assertEquals(80, pos(ALICE).yesLocked());
            assertEquals(-1, place(ALICE, YES, SELL, 700, 21), "only 20 free");
            assertEquals(RejectReason.INSUFFICIENT_POSITION, events.rejects.get(0).reason());
        }

        @Test
        void cannotCancelSomeoneElsesOrder() {
            long id = place(ALICE, YES, BUY, 500, 10);
            assertFalse(engine.cancelOrder(BOB, id, ++ts));
            assertFalse(engine.cancelOrder(ALICE, 999_999, ++ts));
            assertEquals(10, engine.order(id).remaining);
        }

        @Test
        void haltedMarketRejectsNewOrdersButAllowsCancels() {
            long id = place(ALICE, YES, BUY, 500, 10);
            engine.haltMarket(M, ++ts);
            assertEquals(-1, place(BOB, YES, BUY, 500, 10));
            assertEquals(RejectReason.MARKET_NOT_OPEN, events.rejects.get(0).reason());
            assertTrue(engine.cancelOrder(ALICE, id, ++ts));
        }

        @Test
        void invalidPricesAreRejected() {
            assertEquals(-1, place(ALICE, YES, BUY, 0, 1));
            assertEquals(-1, place(ALICE, YES, BUY, 1000, 1));
            assertEquals(RejectReason.INVALID_PRICE, events.rejects.get(1).reason());
        }
    }

    @Nested
    class TimeInForceRules {
        @Test
        void iocCancelsRemainderAndRefundsReservation() {
            seedYes(BOB, 50);
            place(BOB, YES, SELL, 600, 50);
            long id = place(ALICE, YES, BUY, IOC, 999, 80); // a market order
            assertEquals(50, events.trades.get(0).qty());
            assertEquals(new RecordingListener.Cancel(id, 30, CancelReason.IOC_REMAINDER), events.cancels.get(0));
            assertEquals(0, acct(ALICE).reserved());
            assertEquals(DOLLARS_1000 - 50 * 600, acct(ALICE).available());
            assertEquals(0, engine.market(M).book().restingOrders());
        }

        @Test
        void fokRejectsWhenNotFullyFillableAndLeavesBookUntouched() {
            seedYes(BOB, 50);
            place(BOB, YES, SELL, 600, 50);
            assertEquals(-1, place(ALICE, YES, BUY, FOK, 600, 51));
            assertEquals(RejectReason.FOK_NOT_FILLABLE, events.rejects.get(0).reason());
            assertEquals(50, engine.market(M).book().quantityAt(Side.ASK, 600));
            place(ALICE, YES, BUY, FOK, 600, 50);
            assertEquals(1, events.trades.size());
        }

        @Test
        void postOnlyRejectsIfItWouldTake() {
            seedYes(BOB, 50);
            place(BOB, YES, SELL, 600, 50);
            assertEquals(-1, place(ALICE, YES, BUY, POST_ONLY, 600, 10));
            assertTrue(place(ALICE, YES, BUY, POST_ONLY, 599, 10) > 0);
        }
    }

    @Test
    void selfTradePreventionCancelsTheRestingOrder() {
        seedYes(ALICE, 100);
        long resting = place(ALICE, YES, SELL, 600, 100);
        place(BOB, YES, SELL, 610, 0); // rejected, ignore
        place(ALICE, YES, BUY, 600, 10);
        assertTrue(events.trades.isEmpty(), "never trade with yourself");
        assertEquals(CancelReason.SELF_TRADE_PREVENTION, events.cancels.get(0).reason());
        assertEquals(resting, events.cancels.get(0).orderId());
        assertEquals(0, pos(ALICE).yesLocked());
    }

    @Nested
    class Portfolio {
        @Test
        void buyingNoWhileLongYesClosesThePositionViaMerge() {
            place(ALICE, YES, BUY, 600, 100);
            place(BOB, NO, BUY, 400, 100);          // Alice long 100 YES @ 60c
            // Price moves up; Alice exits by buying NO at 30c (== selling YES at 70c).
            place(CAROL, YES, BUY, 700, 100);
            place(ALICE, NO, BUY, 300, 100);

            assertEquals(0, pos(ALICE).yes());
            assertEquals(0, pos(ALICE).no(), "YES+NO merged back into cash");
            assertEquals(10_000, pos(ALICE).realizedPnl(), "+10c x 100");
            assertEquals(DOLLARS_1000 + 10_000, acct(ALICE).cash());
        }

        @Test
        void unrealizedPnlIsMarkToMarket() {
            place(ALICE, YES, BUY, 600, 100);
            place(BOB, NO, BUY, 400, 100);
            Position p = pos(ALICE);
            assertEquals(0, p.unrealizedPnl(600));
            assertEquals(5_000, p.unrealizedPnl(650));
            assertEquals(-60_000, p.unrealizedPnl(0), "worst case: lose the premium");
            assertEquals(600_000, p.avgYesPriceMilli());
        }

        @Test
        void resolutionCancelsOrdersAndPaysWinners() {
            place(ALICE, YES, BUY, 630, 100);
            place(BOB, NO, BUY, 370, 100);
            place(CAROL, YES, BUY, 100, 50); // resting, must be cancelled and refunded

            engine.resolveMarket(M, YES, ++ts);
            assertEquals(MarketStatus.RESOLVED, engine.market(M).status());
            assertEquals(DOLLARS_1000 + 37_000, acct(ALICE).cash(), "paid $1 per YES, cost 63c");
            assertEquals(37_000, pos(ALICE).realizedPnl());
            assertEquals(DOLLARS_1000 - 37_000, acct(BOB).cash());
            assertEquals(DOLLARS_1000, acct(CAROL).cash());
            assertEquals(0, engine.openOrders().size());
            assertEquals(-1, place(ALICE, YES, BUY, 500, 1));
        }
    }

    @Test
    void eventOrderIsDeterministic() {
        seedYes(BOB, 100);
        place(BOB, YES, SELL, 640, 100);
        place(ALICE, YES, BUY, 650, 60);
        assertEquals(java.util.List.of(
                "book 1 ASK@640=100",
                "trade 2 60@640",
                "book 1 ASK@640=40",
                "filled " + engine.lastOrderId()), events.log);
    }
}
