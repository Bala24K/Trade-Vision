package io.tradevision.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real application (journal, engine threads, publisher, HTTP) on a random port with bots and
 * Polymarket disabled, then drives every REST route the terminal uses. Regression guard for routing
 * problems that unit tests cannot see (e.g. parameter-name binding).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        Path dir = Files.createTempDirectory("tv-gw-test");
        r.add("tradevision.data-dir", dir::toString);
        r.add("tradevision.bots.enabled", () -> "false");
        r.add("tradevision.polymarket.enabled", () -> "false");
    }

    record Res(int status, JsonNode body) {}

    Res call(String method, String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isEmpty() ? null : json.readTree(r.body()));
    }

    String order(long market, String outcome, String side, String type, Integer price, long qty, long coid) {
        return """
               {"marketId":%d,"outcome":"%s","side":"%s","type":"%s",%s"quantity":%d,"clientOrderId":%d}"""
                .formatted(market, outcome, side, type, price == null ? "" : "\"price\":" + price + ",", qty, coid);
    }

    @Test
    void fullTradingFlowOverHttp() throws Exception {
        Res markets = call("GET", "/api/markets", null, null);
        assertEquals(200, markets.status());
        assertEquals(8, markets.body().size(), "synthetic markets are bootstrapped");
        long m = markets.body().get(0).get("id").asLong();

        assertEquals(200, call("GET", "/api/markets/" + m + "?levels=10", null, null).status());
        assertEquals(200, call("GET", "/api/markets/" + m + "/candles?tf=1m", null, null).status());
        assertEquals(404, call("GET", "/api/markets/999", null, null).status());

        String alice = call("POST", "/api/session", null, null).body().get("token").asText();
        String bob = call("POST", "/api/session", null, null).body().get("token").asText();
        assertEquals(401, call("GET", "/api/me", "garbage.token", null).status());

        // Alice rests a bid; Bob buys NO against it (mints a pair at Alice's price).
        Res rest = call("POST", "/api/orders", alice, order(m, "YES", "BUY", "LIMIT", 600, 100, 1));
        assertEquals("OPEN", rest.body().get("status").asText());
        long restingId = rest.body().get("orderId").asLong();

        Res take = call("POST", "/api/orders", bob, order(m, "NO", "BUY", "LIMIT", 450, 40, 1));
        assertEquals("FILLED", take.body().get("status").asText());
        assertEquals(400_000, take.body().get("avgPriceMilli").asLong(), "NO filled at 40.0¢ = 100 - 60");

        // Idempotent retry returns the original answer, does not trade again.
        Res retry = call("POST", "/api/orders", bob, order(m, "NO", "BUY", "LIMIT", 450, 40, 1));
        assertEquals(take.body().get("orderId").asLong(), retry.body().get("orderId").asLong());

        JsonNode me = call("GET", "/api/me", alice, null).body();
        assertEquals(40, me.get("positions").get(0).get("yes").asLong());
        assertEquals(1, me.get("openOrders").size());
        assertEquals(60, me.get("openOrders").get(0).get("quantity").asLong() - me.get("openOrders").get(0).get("filled").asLong());

        assertEquals(403, call("DELETE", "/api/orders/" + restingId, bob, null).status(), "cannot cancel another account's order");
        assertEquals(200, call("DELETE", "/api/orders/" + restingId, alice, null).status());
        assertEquals(404, call("DELETE", "/api/orders/" + restingId, alice, null).status());

        JsonNode history = call("GET", "/api/me/orders?status=history", alice, null).body();
        assertEquals("CANCELED", history.get(0).get("status").asText());
        assertEquals(1, call("GET", "/api/me/fills", bob, null).body().size());

        Res bad = call("POST", "/api/orders", alice, order(m, "MAYBE", "BUY", "LIMIT", 500, 1, 2));
        assertEquals(400, bad.status());
        Res broke = call("POST", "/api/orders", alice, order(m, "YES", "BUY", "LIMIT", 999, 1_000_000, 3));
        assertEquals("REJECTED", broke.body().get("status").asText());
        assertEquals("INSUFFICIENT_FUNDS", broke.body().get("reason").asText());

        // Cancel by the client's own id (no exchange order id needed).
        Res resting = call("POST", "/api/orders", alice, order(m, "YES", "BUY", "LIMIT", 100, 5, 777));
        assertEquals("OPEN", resting.body().get("status").asText());
        assertEquals(200, call("DELETE", "/api/orders/client/777", alice, null).status());
        assertEquals(404, call("DELETE", "/api/orders/client/777", alice, null).status(), "already canceled");
        assertEquals(404, call("DELETE", "/api/orders/client/777", bob, null).status(), "ids are per account");

        // Without databases, analytics and history are served from memory and say so.
        JsonNode an = call("GET", "/api/analytics/markets/" + m + "?window=60", null, null).body();
        assertEquals("memory", an.get("source").asText());
        assertEquals(1, an.get("metrics").get("trades").asLong());
        assertEquals(600, an.get("metrics").get("vwap").asDouble(), 1e-9);
        assertEquals(200, call("GET", "/api/analytics/scanner", null, null).status());
        JsonNode lb = call("GET", "/api/analytics/leaderboard", null, null).body();
        assertEquals("memory", lb.get("source").asText());
        assertEquals(2, lb.get("rows").size());
        JsonNode fh = call("GET", "/api/me/history/fills", bob, null).body();
        assertEquals("memory", fh.get("source").asText());
        assertEquals(1, fh.get("items").size());

        assertEquals(403, call("POST", "/api/admin/markets/" + m + "/halt", null, null).status());
        JsonNode sys = call("GET", "/api/system", null, null).body();
        assertTrue(sys.get("durableSequence").asLong() >= sys.get("commandsProcessed").asLong() - 1);
    }
}
