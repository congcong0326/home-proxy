package org.congcong.controlmanager.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MysqlBackupServiceTest {

    @Test
    void exportBackupUsesMysqlDumpAndGzipOutput() throws Exception {
        CapturingCommandRunner runner = new CapturingCommandRunner("CREATE TABLE admin_user(id bigint);");
        MysqlBackupService service = new MysqlBackupService(dataSourceProperties(), runner);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.exportBackup(output);

        assertThat(runner.command).containsExactly(
                "mysqldump",
                "--host", "mysql",
                "--port", "3307",
                "--user", "root",
                "--single-transaction",
                "--routines",
                "--triggers",
                "--events",
                "--hex-blob",
                "--add-drop-table",
                "--databases", "tpproxy"
        );
        assertThat(runner.environment).containsEntry("MYSQL_PWD", "secret");
        assertThat(gunzip(output.toByteArray())).contains("CREATE TABLE admin_user");
    }

    @Test
    void restoreRejectsMissingConfirmationPhrase() {
        MysqlBackupService service = new MysqlBackupService(dataSourceProperties(), new CapturingCommandRunner(""));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "backup.sql",
                "application/sql",
                "SELECT 1;".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.restoreBackup(file, "RESTORE"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("confirmation phrase");
    }

    @Test
    void restoreRecreatesSchemaAndImportsUploadedGzipSql() throws Exception {
        CapturingCommandRunner runner = new CapturingCommandRunner("");
        MysqlBackupService service = new MysqlBackupService(dataSourceProperties(), runner);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "backup.sql.gz",
                "application/gzip",
                gzip("INSERT INTO admin_user(id) VALUES (1);")
        );

        service.restoreBackup(file, "RESTORE MYSQL");

        assertThat(runner.command).containsExactly(
                "mysql",
                "--host", "mysql",
                "--port", "3307",
                "--user", "root"
        );
        assertThat(runner.environment).containsEntry("MYSQL_PWD", "secret");
        assertThat(runner.stdin)
                .contains("DROP DATABASE IF EXISTS `tpproxy`;")
                .contains("CREATE DATABASE `tpproxy` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
                .contains("INSERT INTO admin_user(id) VALUES (1);");
    }

    private static DataSourceProperties dataSourceProperties() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl("jdbc:mysql://mysql:3307/tpproxy?useSSL=false&serverTimezone=UTC");
        properties.setUsername("root");
        properties.setPassword("secret");
        return properties;
    }

    private static byte[] gzip(String value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static String gunzip(byte[] value) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(value))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static class CapturingCommandRunner implements MysqlCommandRunner {
        private final String stdout;
        private List<String> command;
        private Map<String, String> environment;
        private String stdin;

        private CapturingCommandRunner(String stdout) {
            this.stdout = stdout;
        }

        @Override
        public MysqlCommandResult run(List<String> command, Map<String, String> environment,
                                      java.io.InputStream stdin, java.io.OutputStream stdout) throws Exception {
            this.command = List.copyOf(command);
            this.environment = Map.copyOf(environment);
            this.stdin = stdin == null ? "" : new String(stdin.readAllBytes(), StandardCharsets.UTF_8);
            stdout.write(this.stdout.getBytes(StandardCharsets.UTF_8));
            return new MysqlCommandResult(0, "");
        }
    }
}
