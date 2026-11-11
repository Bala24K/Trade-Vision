package io.tradevision.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole system with both projections: real app on a random port, real Postgres and ClickHouse in Docker.
 * Trades go in over HTTP; history, leaderboard and analytics must come back from the databases.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullStackIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.6-alpine");

    @Container
    static final ClickHouseContainer CH = new ClickHouseContainer("clickhouse/clickhouse-server:24.8.8.17-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("tv-fullstack").toString();
        r.add("tradevision.data-dir", () -> dir);
        r.add("tradevision.bots.enabled", () -> "false");
        r.add("tradevision.polymarket.enabled", () -> "false");
        r.add("tradevision.postgres.enabled", () -> "true");
        r.add("tradevision.postgres.url", PG::getJdbcUrl);
        r.add("tradevision.postgres.user", PG::getUsername);
        r.add("tradevision.postgres.password", PG::getPassword);
        r.add("tradevision.clickhouse.enabled", () -> "true");
        r.add("tradevision.clickhouse.url", () -> "http://" + CH.getHost() + ":" + CH.getMappedPort(8123));
        r.add("tradevision.clickhouse.user", CH::getUsername);
        r.add("tradevision.clickhouse.password", CH::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    final HttpClient http = HttpClient.newHttpClient();

    JsonNode call(String method, String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.body().isEmpty() ? null : json.readTree(r.body());
    }

    JsonNode waitFor(String path, String token, Predicate<JsonNode> ok) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = call("GET", path, token, null);
            if (last != null && ok.test(last)) return last;
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting on " + path + ", last: " + last);
    }

    static String order(long market, String outcome, int price, long qty, long coid) {
        return """
               {"marketId":%d,"outcome":"%s","side":"BUY","type":"LIMIT","price":%d,"quantity":%d,"clientOrderId":%d}"""
                .formatted(market, outcome, price, qty, coid);
    }

    @Test
    void historyLeaderboardAndAnalyticsComeFromTheDatabases() throws Exception {
        long m = call("GET", "/api/markets", null, null).get(0).get("id").asLong();
        String alice = call("POST", "/api/session", null, null).get("token").asText();
        String bob = call("POST", "/api/session", null, null).get("token").asText();

        // Five trades between Alice (YES) and Bob (NO) at rising prices.
        for (int i = 0; i < 5; i++) {
            int px = 500 + i * 10;
            assertEquals("OPEN", call("POST", "/api/orders", alice, order(m, "YES", px, 10, 100 + i)).get("status").asText());
            assertEquals("FILLED", call("POST", "/api/orders", bob, order(m, "NO", 1000 - px, 10, 200 + i)).get("status").asText());
        }

        // Postgres catches up (lag 0) shortly after the trades are durable.
        waitFor("/api/system", null, s -> s.path("postgres").path("lag").asLong(-1) == 0
                && s.path("postgres").path("status").path("state").asText().equals("UP"));

        // Keyset pagination over the full fill history.
        JsonNode p1 = call("GET", "/api/me/history/fills?limit=2", alice, null);
        assertEquals("postgres", p1.get("source").asText());
        assertEquals(2, p1.get("items").size());
        assertEquals(540, p1.get("items").get(0).get("price").asInt(), "newest first");
        long next = p1.get("next").asLong();
        JsonNode p2 = call("GET", "/api/me/history/fills?limit=2&before=" + next, alice, null);
        assertEquals(520, p2.get("items").get(0).get("price").asInt());
        JsonNode p3 = call("GET", "/api/me/history/fills?limit=2&before=" + p2.get("next").asLong(), alice, null);
        assertEquals(1, p3.get("items").size());
        assertTrue(p3.get("next").isNull(), "last page");

        JsonNode orders = call("GET", "/api/me/history/orders?limit=10", bob, null);
        assertEquals("postgres", orders.get("source").asText());
        assertEquals(5, orders.get("items").size());
        assertEquals("FILLED", orders.get("items").get(0).get("status").asText());

        JsonNode lb = call("GET", "/api/analytics/leaderboard", null, null);
        assertEquals("postgres", lb.get("source").asText());
        assertEquals(2, lb.get("rows").size());
        // Zero-sum: whatever Alice made marked-to-market, Bob lost (no fees).
        assertEquals(0, lb.get("rows").get(0).get("totalPnl").asLong() + lb.get("rows").get(1).get("totalPnl").asLong());

        // ClickHouse: analytics aggregated server-side over the trades table.
        JsonNode an = waitFor("/api/analytics/markets/" + m + "?window=60", null,
                a -> a.get("source").asText().equals("clickhouse") && a.get("metrics").get("trades").asLong() == 5);
        JsonNode metrics = an.get("metrics");
        assertEquals(50, metrics.get("volume").asLong());
        assertEquals(520.0, metrics.get("vwap").asDouble(), 1e-9, "(500+510+520+530+540)*10 / 50");
        assertEquals(500, metrics.get("open").asInt());
        assertEquals(540, metrics.get("last").asInt());
        assertEquals(50, metrics.get("sellVolume").asLong(), "Bob's NO buys lift YES bids: aggressor = ASK");
        assertNotNull(an.get("live"));

        JsonNode sys = call("GET", "/api/system", null, null);
        assertTrue(sys.get("clickhouse").get("up").asBoolean());
        assertNull(sys.get("clickhouse").get("lastError").textValue());
    }
}
