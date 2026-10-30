package org.congcong.controlmanager.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.congcong.controlmanager.service.SqliteBackupService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.sqlite.SQLiteConfig;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * SQLite metadata database. Every pooled connection gets WAL, foreign keys, a busy timeout and
 * {@code BEGIN IMMEDIATE} transactions, so a read-then-write transaction never fails with a stale snapshot.
 */
@Configuration
public class DataSourceConfig {
    @Bean
    @Primary
    public DataSource dataSource(@Value("${spring.datasource.url}") String url,
                                 @Value("${home-proxy.sqlite.busy-timeout-ms:10000}") int busyTimeoutMs,
                                 @Value("${home-proxy.sqlite.pool-size:4}") int poolSize) {
        Path dbFile = DataSourcePaths.databaseFile(url);
        if (dbFile != null) {
            try {
                Path parent = dbFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create database directory for " + dbFile, e);
            }
            SqliteBackupService.applyPendingRestore(dbFile);
        }

        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(busyTimeoutMs);
        sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        Properties properties = sqlite.toProperties();

        HikariConfig config = new HikariConfig();
        config.setPoolName("sqlite-metadata");
        config.setJdbcUrl(url);
        config.setDataSourceProperties(properties);
        config.setMaximumPoolSize(Math.max(1, poolSize));
        return new HikariDataSource(config);
    }
}
