package org.congcong.controlmanager.config;

import java.nio.file.Path;

public final class DataSourcePaths {
    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    private DataSourcePaths() {
    }

    /** Returns the database file for a {@code jdbc:sqlite:<path>} URL, or null for in-memory/unsupported URLs. */
    public static Path databaseFile(String url) {
        if (url == null || !url.startsWith(JDBC_PREFIX)) {
            return null;
        }
        String file = url.substring(JDBC_PREFIX.length());
        int query = file.indexOf('?');
        if (query >= 0) {
            file = file.substring(0, query);
        }
        if (file.isBlank() || file.startsWith(":memory:") || file.startsWith("file:")) {
            return null;
        }
        return Path.of(file);
    }
}
