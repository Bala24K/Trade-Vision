package io.tradevision.gateway.projection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.tradevision.gateway.TvProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Postgres is a projection, not a dependency: the exchange must start and trade while the database is down.
 * So the pool is created lazily ({@code initializationFailTimeout = -1}) and migrations run from the projector's
 * own thread on first successful connection, not at application startup.
 */
@Configuration
@ConditionalOnProperty(name = "tradevision.postgres.enabled", havingValue = "true")
public class PostgresConfig {
    @Bean(destroyMethod = "close")
    public DataSource projectionDataSource(TvProperties props) {
        var c = new HikariConfig();
        c.setJdbcUrl(props.postgres().url());
        c.setUsername(props.postgres().user());
        c.setPassword(props.postgres().password());
        c.setPoolName("tv-pg");
        c.setMaximumPoolSize(6);           // 1 projector writer + a few concurrent API readers
        c.setInitializationFailTimeout(-1); // never fail application startup because the projection is down
        c.setConnectionTimeout(3_000);
        c.addDataSourceProperty("reWriteBatchedInserts", "true"); // pgjdbc: batch of INSERTs -> multi-row INSERT
        c.addDataSourceProperty("socketTimeout", "30");           // a hung server must not hang the writer forever
        return new HikariDataSource(c);
    }
}
