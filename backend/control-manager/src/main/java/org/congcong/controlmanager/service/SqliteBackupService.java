package org.congcong.controlmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteConfig;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Online backup of the SQLite metadata database via {@code VACUUM INTO}, plus staged restore.
 * <p>
 * The live database file (and its {@code -wal}) is never copied while the application writes to it.
 * A restore is staged next to the database as {@code <db>.restore-pending} and swapped in at the next
 * startup, before the connection pool opens the file ({@link #applyPendingRestore(Path)}).
 */
@Slf4j
@Service
public class SqliteBackupService {
    public static final String RESTORE_CONFIRMATION_PHRASE = "RESTORE DATABASE";
    static final String PENDING_SUFFIX = ".restore-pending";
    static final String BACKUP_PREFIX = "home-proxy-";
    static final String PRE_RESTORE_PREFIX = "pre-restore-";
    private static final DateTimeFormatter FILENAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    private final DataSource dataSource;
    private final Path databaseFile;
    private final Path backupDir;
    private final int retention;
    private final boolean scheduledEnabled;

    public SqliteBackupService(DataSource dataSource,
                               @Value("${spring.datasource.url}") String datasourceUrl,
                               @Value("${home-proxy.backup.dir:}") String backupDir,
                               @Value("${home-proxy.backup.retention:7}") int retention,
                               @Value("${home-proxy.backup.enabled:true}") boolean scheduledEnabled) {
        this.dataSource = dataSource;
        this.databaseFile = org.congcong.controlmanager.config.DataSourcePaths.databaseFile(datasourceUrl);
        this.backupDir = resolveBackupDir(databaseFile, backupDir);
        this.retention = Math.max(1, retention);
        this.scheduledEnabled = scheduledEnabled;
    }

    private static Path resolveBackupDir(Path databaseFile, String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path parent = databaseFile == null ? null : databaseFile.toAbsolutePath().getParent();
        return (parent == null ? Path.of(".") : parent).resolve("backups");
    }

    /** Streams a freshly created consistent snapshot of the database. */
    public void exportBackup(OutputStream output) {
        Path snapshot = null;
        try {
            Files.createDirectories(backupDir);
            snapshot = backupDir.resolve(".export-" + FILENAME_TIME.format(LocalDateTime.now()) + ".tmp");
            vacuumInto(snapshot);
            Files.copy(snapshot, output);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "export database backup failed", e);
        } finally {
            deleteQuietly(snapshot);
        }
    }

    /** Creates a backup file in the backup directory and prunes old ones. */
    public Path createBackup() {
        try {
            Files.createDirectories(backupDir);
            Path target = backupDir.resolve(BACKUP_PREFIX + FILENAME_TIME.format(LocalDateTime.now()) + ".db");
            vacuumInto(target);
            pruneOldBackups();
            return target;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "create database backup failed", e);
        }
    }

    @Scheduled(cron = "${home-proxy.backup.cron:0 30 3 * * *}")
    public void scheduledBackup() {
        if (!scheduledEnabled || databaseFile == null) {
            return;
        }
        try {
            Path file = createBackup();
            log.info("Database backup created: {}", file);
        } catch (Exception e) {
            log.error("Scheduled database backup failed", e);
        }
    }

    public List<Path> listBackups() {
        if (!Files.isDirectory(backupDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(backupDir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(BACKUP_PREFIX)
                            && p.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "list database backups failed", e);
        }
    }

    private void pruneOldBackups() {
        List<Path> backups = listBackups();
        for (int i = retention; i < backups.size(); i++) {
            deleteQuietly(backups.get(i));
        }
    }

    /** Validates an uploaded SQLite file and stages it; it replaces the live database on next startup. */
    public void restoreBackup(MultipartFile file, String confirmationPhrase) {
        if (!RESTORE_CONFIRMATION_PHRASE.equals(confirmationPhrase)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "confirmation phrase must be " + RESTORE_CONFIRMATION_PHRASE);
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "backup file is required");
        }
        if (databaseFile == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "spring.datasource.url must point to a SQLite file");
        }
        Path staged = pendingFile(databaseFile);
        Path upload = staged.resolveSibling(staged.getFileName() + ".upload");
        try {
            Files.createDirectories(backupDir);
            try (var in = file.getInputStream()) {
                Files.copy(in, upload, StandardCopyOption.REPLACE_EXISTING);
            }
            validateBackupFile(upload);
            Files.move(upload, staged, StandardCopyOption.REPLACE_EXISTING);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "stage database restore failed", e);
        } finally {
            deleteQuietly(upload);
        }
    }

    static void validateBackupFile(Path file) {
        try {
            byte[] header = new byte[SQLITE_HEADER.length];
            try (var in = Files.newInputStream(file)) {
                if (in.readNBytes(header, 0, header.length) != header.length || !Arrays.equals(header, SQLITE_HEADER)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "backup file is not a SQLite database");
                }
            }
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), config.toProperties());
                 Statement statement = connection.createStatement()) {
                try (ResultSet rs = statement.executeQuery("PRAGMA integrity_check")) {
                    if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "backup file failed integrity_check");
                    }
                }
                for (String table : List.of("admin_user", "proxy_worker", "flyway_schema_history")) {
                    try (ResultSet rs = statement.executeQuery(
                            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
                        if (!rs.next()) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "backup file is missing table " + table);
                        }
                    }
                }
            }
        } catch (SQLException | IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "backup file cannot be read as SQLite: " + e.getMessage(), e);
        }
    }

    /**
     * Swaps a staged restore in before the database is opened. The previous database is kept in the backup
     * directory as {@code pre-restore-<time>.db}.
     */
    public static void applyPendingRestore(Path databaseFile) {
        Path pending = pendingFile(databaseFile);
        if (!Files.exists(pending)) {
            return;
        }
        try {
            Path backups = databaseFile.toAbsolutePath().getParent().resolve("backups");
            if (Files.exists(databaseFile)) {
                Files.createDirectories(backups);
                Path previous = backups.resolve(PRE_RESTORE_PREFIX + FILENAME_TIME.format(LocalDateTime.now()) + ".db");
                // The pool is not open yet, so no writer is active; open once to fold the WAL into the main file.
                checkpoint(databaseFile);
                Files.move(databaseFile, previous, StandardCopyOption.REPLACE_EXISTING);
                log.warn("Previous database kept at {}", previous);
            }
            Files.deleteIfExists(sibling(databaseFile, "-wal"));
            Files.deleteIfExists(sibling(databaseFile, "-shm"));
            Files.move(pending, databaseFile, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Database restored from staged backup: {}", databaseFile);
        } catch (IOException e) {
            throw new IllegalStateException("cannot apply pending database restore " + pending, e);
        }
    }

    private static void checkpoint(Path databaseFile) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException e) {
            log.warn("WAL checkpoint before restore failed: {}", e.getMessage());
        }
    }

    static Path pendingFile(Path databaseFile) {
        return databaseFile.resolveSibling(databaseFile.getFileName() + PENDING_SUFFIX);
    }

    private static Path sibling(Path file, String suffix) {
        return file.resolveSibling(file.getFileName() + suffix);
    }

    private void vacuumInto(Path target) {
        Objects.requireNonNull(target);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            Files.deleteIfExists(target);
            statement.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
        } catch (SQLException | IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "database backup failed", e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Cannot delete {}: {}", path, e.getMessage());
        }
    }
}
