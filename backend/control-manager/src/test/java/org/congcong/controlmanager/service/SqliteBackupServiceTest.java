package org.congcong.controlmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteDataSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteBackupServiceTest {
    @TempDir
    Path dir;

    private Path db() {
        return dir.resolve("home-proxy.db");
    }

    private SQLiteDataSource seed(Path file, String marker) throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file);
        ds.setJournalMode("WAL");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS admin_user (id INTEGER PRIMARY KEY, username TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS proxy_worker (worker_id TEXT PRIMARY KEY)");
            s.execute("CREATE TABLE IF NOT EXISTS flyway_schema_history (version TEXT)");
            s.execute("INSERT INTO admin_user (username) VALUES ('" + marker + "')");
        }
        return ds;
    }

    private SqliteBackupService service(SQLiteDataSource ds, int retention) {
        return new SqliteBackupService(ds, "jdbc:sqlite:" + db(), "", retention, true);
    }

    private static String marker(Path file) throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT username FROM admin_user ORDER BY id DESC LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void exportProducesConsistentSnapshotWhileDatabaseIsInWalMode() throws Exception {
        SqliteBackupService service = service(seed(db(), "live"), 3);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportBackup(out);

        Path exported = dir.resolve("exported.db");
        Files.write(exported, out.toByteArray());
        assertThat(marker(exported)).isEqualTo("live");
        try (var files = Files.list(dir.resolve("backups"))) {
            assertThat(files).as("temporary export snapshot is removed").isEmpty();
        }
    }

    @Test
    void createBackupKeepsOnlyRetentionNewestFiles() throws Exception {
        SqliteBackupService service = service(seed(db(), "live"), 2);

        Path first = service.createBackup();
        Thread.sleep(5);
        service.createBackup();
        Thread.sleep(5);
        Path third = service.createBackup();

        assertThat(service.listBackups()).hasSize(2).first().isEqualTo(third);
        assertThat(first).doesNotExist();
        assertThat(marker(third)).isEqualTo("live");
    }

    @Test
    void restoreRejectsWrongPhraseMissingFileAndNonSqliteContent() throws Exception {
        SqliteBackupService service = service(seed(db(), "live"), 3);
        MockMultipartFile garbage = new MockMultipartFile("file", "x.db", "application/octet-stream", "not sqlite at all".getBytes());

        assertThatThrownBy(() -> service.restoreBackup(garbage, "RESTORE"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("confirmation phrase");
        assertThatThrownBy(() -> service.restoreBackup(null, SqliteBackupService.RESTORE_CONFIRMATION_PHRASE))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("required");
        assertThatThrownBy(() -> service.restoreBackup(garbage, SqliteBackupService.RESTORE_CONFIRMATION_PHRASE))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not a SQLite database");
        assertThat(SqliteBackupService.pendingFile(db())).doesNotExist();
    }

    @Test
    void restoreRejectsSqliteFileWithoutApplicationSchema() throws Exception {
        SqliteBackupService service = service(seed(db(), "live"), 3);
        Path other = dir.resolve("other.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + other);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INTEGER)");
        }
        MockMultipartFile upload = new MockMultipartFile("file", "other.db", "application/octet-stream", Files.readAllBytes(other));

        assertThatThrownBy(() -> service.restoreBackup(upload, SqliteBackupService.RESTORE_CONFIRMATION_PHRASE))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("missing table");
        assertThat(SqliteBackupService.pendingFile(db())).doesNotExist();
    }

    @Test
    void stagedRestoreReplacesDatabaseOnNextStartAndKeepsPreviousCopy() throws Exception {
        SQLiteDataSource live = seed(db(), "before");
        SqliteBackupService service = service(live, 3);
        Path snapshotSource = dir.resolve("snapshot-source.db");
        seed(snapshotSource, "from-backup");
        MockMultipartFile upload = new MockMultipartFile("file", "backup.db", "application/octet-stream",
                Files.readAllBytes(snapshotSource));

        service.restoreBackup(upload, SqliteBackupService.RESTORE_CONFIRMATION_PHRASE);

        assertThat(marker(db())).as("live database untouched until restart").isEqualTo("before");
        assertThat(SqliteBackupService.pendingFile(db())).exists();

        SqliteBackupService.applyPendingRestore(db());

        assertThat(marker(db())).isEqualTo("from-backup");
        assertThat(SqliteBackupService.pendingFile(db())).doesNotExist();
        Path[] previous;
        try (var files = Files.list(dir.resolve("backups"))) {
            previous = files.filter(p -> p.getFileName().toString().startsWith(SqliteBackupService.PRE_RESTORE_PREFIX))
                    .toArray(Path[]::new);
        }
        assertThat(previous).hasSize(1);
        assertThat(marker(previous[0])).isEqualTo("before");
    }

    @Test
    void applyPendingRestoreIsNoOpWithoutStagedFile() throws Exception {
        seed(db(), "keep");
        SqliteBackupService.applyPendingRestore(db());
        assertThat(marker(db())).isEqualTo("keep");
    }
}
