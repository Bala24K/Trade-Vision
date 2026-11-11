package io.tradevision.gateway.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradevision.gateway.TvProperties;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal ClickHouse client over its HTTP interface (port 8123). No driver dependency: inserts are
 * {@code INSERT ... FORMAT JSONEachRow} with newline-delimited JSON bodies, queries return JSONEachRow.
 * User input is never concatenated into SQL: values travel as typed server-side parameters
 * ({@code {name:Type}} placeholders + {@code param_name} URL arguments).
 */
public final class ClickHouseClient {
    /** Server answered with an error status. 4xx = the request itself is bad and retrying it cannot help. */
    public static final class ClickHouseException extends IOException {
        public final int status;

        ClickHouseException(int status, String message) {
            super(message);
            this.status = status;
        }

        public boolean retryable() {
            return status >= 500;
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final String base, database, user, password;

    public ClickHouseClient(TvProperties.ClickHouse cfg, ObjectMapper json) {
        this.json = json;
        this.base = cfg.url().endsWith("/") ? cfg.url() : cfg.url() + "/";
        this.database = cfg.database();
        this.user = cfg.user();
        this.password = cfg.password() == null ? "" : cfg.password();
    }

    public String database() {
        return database;
    }

    /** DDL or other statements without results. {@code db=false} runs outside the configured database. */
    public void execute(String sql, boolean db) throws IOException {
        send(sql, null, Map.of(), db);
    }

    public void insert(String table, CharSequence jsonEachRow) throws IOException {
        if (jsonEachRow.isEmpty()) return;
        send("INSERT INTO " + table + " FORMAT JSONEachRow", jsonEachRow.toString(), Map.of(), true);
    }

    public List<JsonNode> query(String sql, Map<String, Object> params) throws IOException {
        String body = send(sql + " FORMAT JSONEachRow", null, params, true);
        List<JsonNode> rows = new ArrayList<>();
        for (String line : body.split("\n")) if (!line.isBlank()) rows.add(json.readTree(line));
        return rows;
    }

    private String send(String sql, String body, Map<String, Object> params, boolean db) throws IOException {
        StringBuilder url = new StringBuilder(base).append("?query=").append(enc(sql));
        if (db) url.append("&database=").append(enc(database));
        params.forEach((k, v) -> url.append("&param_").append(k).append('=').append(enc(String.valueOf(v))));
        var req = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(10))
                .header("X-ClickHouse-User", user)
                .header("X-ClickHouse-Key", password)
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new ClickHouseException(r.statusCode(),
                        "clickhouse " + r.statusCode() + ": " + r.body().lines().findFirst().orElse(""));
            }
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
