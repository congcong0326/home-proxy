package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
@RequiredArgsConstructor
public class MysqlBackupService {
    public static final String RESTORE_CONFIRMATION_PHRASE = "RESTORE MYSQL";

    private final DataSourceProperties dataSourceProperties;
    private final MysqlCommandRunner commandRunner;

    public void exportBackup(OutputStream output) {
        MysqlConnectionInfo connection = MysqlConnectionInfo.from(dataSourceProperties);
        List<String> command = List.of(
                "mysqldump",
                "--host", connection.host(),
                "--port", String.valueOf(connection.port()),
                "--user", connection.username(),
                "--single-transaction",
                "--routines",
                "--triggers",
                "--events",
                "--hex-blob",
                "--add-drop-table",
                "--databases", connection.database()
        );

        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            runCommand(command, connection.environment(), null, gzip);
            gzip.finish();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "export mysql backup failed", e);
        }
    }

    public void restoreBackup(MultipartFile file, String confirmationPhrase) {
        if (!RESTORE_CONFIRMATION_PHRASE.equals(confirmationPhrase)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmation phrase must be RESTORE MYSQL");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "backup file is required");
        }
        String filename = Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase();
        if (!filename.endsWith(".sql") && !filename.endsWith(".sql.gz") && !filename.endsWith(".gz")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "backup file must be .sql or .sql.gz");
        }

        MysqlConnectionInfo connection = MysqlConnectionInfo.from(dataSourceProperties);
        List<String> command = List.of(
                "mysql",
                "--host", connection.host(),
                "--port", String.valueOf(connection.port()),
                "--user", connection.username()
        );

        String resetSql = "DROP DATABASE IF EXISTS " + quoteIdentifier(connection.database()) + ";\n"
                + "CREATE DATABASE " + quoteIdentifier(connection.database())
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n";

        try (InputStream resetInput = new ByteArrayInputStream(resetSql.getBytes(StandardCharsets.UTF_8));
             InputStream backupInput = openBackupInput(file, filename);
             InputStream combinedInput = new java.io.SequenceInputStream(resetInput, backupInput)) {
            runCommand(command, connection.environment(), combinedInput, OutputStream.nullOutputStream());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "restore mysql backup failed", e);
        }
    }

    private void runCommand(List<String> command, Map<String, String> environment,
                            InputStream stdin, OutputStream stdout) {
        MysqlCommandResult result;
        try {
            result = commandRunner.run(command, environment, stdin, stdout);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "mysql client command unavailable: " + command.get(0), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "mysql client command interrupted: " + command.get(0), e);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "mysql client command failed: " + command.get(0), e);
        }

        if (result.exitCode() != 0) {
            String stderr = result.stderr() == null || result.stderr().isBlank()
                    ? "exit code " + result.exitCode()
                    : result.stderr().trim();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "mysql client command failed: " + stderr);
        }
    }

    private static InputStream openBackupInput(MultipartFile file, String filename) throws Exception {
        InputStream input = file.getInputStream();
        if (filename.endsWith(".gz")) {
            return new GZIPInputStream(input);
        }
        return input;
    }

    private static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    record MysqlConnectionInfo(String host, int port, String database, String username, String password) {
        private static MysqlConnectionInfo from(DataSourceProperties properties) {
            String url = properties.getUrl();
            if (url == null || !url.startsWith("jdbc:mysql://")) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "spring.datasource.url must be a MySQL JDBC URL");
            }

            URI uri = URI.create(url.substring("jdbc:".length()));
            String database = uri.getPath();
            if (database == null || database.length() <= 1) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "spring.datasource.url must include database name");
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "spring.datasource.url must include host");
            }

            String decodedDatabase = URLDecoder.decode(database.substring(1), StandardCharsets.UTF_8);
            return new MysqlConnectionInfo(
                    host,
                    uri.getPort() > 0 ? uri.getPort() : 3306,
                    decodedDatabase,
                    Objects.requireNonNullElse(properties.getUsername(), ""),
                    Objects.requireNonNullElse(properties.getPassword(), "")
            );
        }

        private Map<String, String> environment() {
            return password == null || password.isEmpty()
                    ? Map.of()
                    : Map.of("MYSQL_PWD", password);
        }
    }
}
