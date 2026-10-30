package org.congcong.controlmanager.telemetry;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin JDBC access to the telemetry SQLite file. Time columns are epoch microseconds (UTC), so
 * {@link Instant}/{@link Timestamp} parameters are bound as microseconds and booleans as 0/1.
 */
@Component
public class TelemetryJdbcClient {
    private static final DateTimeFormatter UTC_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
            .withZone(ZoneOffset.UTC);

    private final DataSource dataSource;

    public TelemetryJdbcClient(@Qualifier("telemetryDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public int execute(String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setParams(stmt, params);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** All rows are written in one transaction: either the whole batch is stored or none of it. */
    public int[] batchExecute(String sql, List<List<Object>> batchParams) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (List<Object> params : batchParams) {
                    setParams(stmt, params.toArray());
                    stmt.addBatch();
                }
                int[] result = stmt.executeBatch();
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Map<String, Object>> query(String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** {@code PRAGMA user_version}: 0 for a fresh file, otherwise the telemetry schema version. */
    public int userVersion() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("PRAGMA user_version");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static long micros(Instant time) {
        return time.getEpochSecond() * 1_000_000 + time.getNano() / 1000;
    }

    /** Reads a stored epoch-microsecond column; null stays null. */
    public static Instant instant(Object micros) {
        if (micros == null) return null;
        long value = ((Number) micros).longValue();
        return Instant.ofEpochSecond(Math.floorDiv(value, 1_000_000L), Math.floorMod(value, 1_000_000L) * 1000L);
    }

    /** {@code yyyy-MM-dd HH:mm:ss.SSSSSS} in UTC, the text form the overview footnotes already display. */
    public static String utcText(Object micros) {
        Instant time = instant(micros);
        return time == null ? null : UTC_TEXT.format(time);
    }

    private void setParams(PreparedStatement stmt, Object... params) throws SQLException {
        if (params == null) return;
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            if (value instanceof Timestamp time) value = micros(time.toInstant());
            else if (value instanceof Instant time) value = micros(time);
            else if (value instanceof Boolean flag) value = flag ? 1 : 0;
            stmt.setObject(i + 1, value);
        }
    }
}
