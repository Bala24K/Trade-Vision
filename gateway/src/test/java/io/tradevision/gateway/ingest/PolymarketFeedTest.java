package io.tradevision.gateway.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradevision.engine.journal.Journal;
import io.tradevision.gateway.TvProperties;
import io.tradevision.gateway.catalog.MarketCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fixture-driven tests of message normalisation and the integrity rules (stale / duplicate / crossed). */
class PolymarketFeedTest {
    @TempDir
    Path tmp;

    static final String TOKEN = "123";

    PolymarketFeed feed() {
        var props = new TvProperties(tmp.toString(), Journal.SyncPolicy.OS_CACHE, 0, "INLINE", false, "x", 0,
                new TvProperties.Bots(false), new TvProperties.Polymarket(true, 0, "http://invalid", "ws://invalid"),
                null, null);
        var f = new PolymarketFeed(props, new MarketCatalog(props, new ObjectMapper()), new ObjectMapper());
        f.bookFor(TOKEN);
        return f;
    }

    @Test
    void snapshotThenDeltasInBothFormats() throws Exception {
        var f = feed();
        f.handle("""
                [{"event_type":"book","asset_id":"123","timestamp":"1000","hash":"h0",
                  "bids":[{"price":"0.52","size":"100"},{"price":"0.51","size":"50"}],
                  "asks":[{"price":"0.55","size":"80"}]}]""");
        var q = f.bookFor(TOKEN).quote();
        assertEquals(520, q.bestBid());
        assertEquals(550, q.bestAsk());

        // current format
        f.handle("""
                {"event_type":"price_change","timestamp":"1001","price_changes":[
                  {"asset_id":"123","price":"0.53","size":"10","side":"BUY","hash":"h1"}]}""");
        assertEquals(530, f.bookFor(TOKEN).quote().bestBid());

        // legacy format; size 0 removes the level
        f.handle("""
                {"event_type":"price_change","asset_id":"123","timestamp":"1002","hash":"h2",
                 "changes":[{"price":"0.53","side":"BUY","size":"0"}]}""");
        assertEquals(520, f.bookFor(TOKEN).quote().bestBid());
        assertEquals(3, f.stats().applied());
    }

    @Test
    void staleAndDuplicateDeltasAreDropped() throws Exception {
        var f = feed();
        f.handle("""
                {"event_type":"book","asset_id":"123","timestamp":"2000","bids":[{"price":"0.40","size":"5"}],"asks":[{"price":"0.45","size":"5"}]}""");
        f.handle("""
                {"event_type":"price_change","timestamp":"1999","price_changes":[{"asset_id":"123","price":"0.44","size":"9","side":"BUY","hash":"a"}]}""");
        f.handle("""
                {"event_type":"price_change","timestamp":"2001","price_changes":[{"asset_id":"123","price":"0.41","size":"9","side":"BUY","hash":"b"}]}""");
        f.handle("""
                {"event_type":"price_change","timestamp":"2002","price_changes":[{"asset_id":"123","price":"0.42","size":"9","side":"BUY","hash":"b"}]}""");
        assertEquals(410, f.bookFor(TOKEN).quote().bestBid(), "stale 0.44 and duplicate 0.42 ignored");
        assertEquals(1, f.stats().stale());
        assertEquals(1, f.stats().duplicates());
    }

    @Test
    void crossedBookIsDetected() {
        var b = new ReferenceBook();
        b.snapshot(1, new int[] {500}, new long[] {1}, new int[] {510}, new long[] {1});
        assertEquals(ReferenceBook.Apply.CROSSED, b.delta(2, "x", true, 515, 3));
    }

    @Test
    void nonJsonFramesAreIgnored() throws Exception {
        var f = feed();
        f.handle("PONG");
        assertEquals(0, f.stats().events());
    }
}
