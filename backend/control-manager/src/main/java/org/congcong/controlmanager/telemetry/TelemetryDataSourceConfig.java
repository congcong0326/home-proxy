package org.congcong.controlmanager.telemetry;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.congcong.controlmanager.config.DataSourcePaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Telemetry facts live in their own SQLite file: a high-volume single writer (the flush task) never
 * contends with metadata transactions, and the file can be dropped and rebuilt without touching configuration.
 */
@Configuration
public class TelemetryDataSourceConfig {
    @Bean(name = "telemetryDataSource")
    public DataSource telemetryDataSource(@Value("${observability.sqlite.url}") String url,
                                          @Value("${observability.sqlite.busy-timeout-ms:10000}") int busyTimeoutMs,
                                          @Value("${observability.sqlite.pool-size:4}") int poolSize) {
        Path dbFile = DataSourcePaths.databaseFile(url);
        if (dbFile != null) {
            try {
                Path parent = dbFile.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create telemetry database directory for " + dbFile, e);
            }
        }
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqlite.setBusyTimeout(busyTimeoutMs);
        sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);

        HikariConfig config = new HikariConfig();
        config.setPoolName("sqlite-telemetry");
        config.setJdbcUrl(url);
        config.setDataSourceProperties(sqlite.toProperties());
        config.setMaximumPoolSize(Math.max(1, poolSize));
        return new HikariDataSource(config);
    }
}
