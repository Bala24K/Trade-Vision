package io.tradevision.gateway.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradevision.gateway.TvProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Market metadata (question, category, source, external ids). Deliberately <i>not</i> engine state: the engine
 * only knows numeric market ids, so presentation data can change without touching the journal.
 * Persisted as JSON next to the journal.
 */
@Component
public class MarketCatalog {
    public enum Source { SYNTHETIC, POLYMARKET }

    public record MarketMeta(long id, String question, String category, Source source, String slug, String yesTokenId,
                             String closesAt, String image, int seedPrice) {}

    private final ObjectMapper json;
    private final Path file;
    private final CopyOnWriteArrayList<MarketMeta> markets = new CopyOnWriteArrayList<>();

    public MarketCatalog(TvProperties props, ObjectMapper json) {
        this.json = json;
        this.file = Path.of(props.dataDir()).resolve("markets.json");
        try {
            if (Files.exists(file)) markets.addAll(json.readValue(file.toFile(), new TypeReference<List<MarketMeta>>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    public List<MarketMeta> all() {
        return List.copyOf(markets);
    }

    public Optional<MarketMeta> get(long id) {
        return markets.stream().filter(m -> m.id() == id).findFirst();
    }

    public long nextId() {
        return markets.stream().mapToLong(MarketMeta::id).max().orElse(0) + 1;
    }

    public synchronized void add(MarketMeta m) {
        markets.add(m);
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("markets.json.tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new ArrayList<>(markets));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
