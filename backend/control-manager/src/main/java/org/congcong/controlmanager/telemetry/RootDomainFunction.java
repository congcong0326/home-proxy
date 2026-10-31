package org.congcong.controlmanager.telemetry;

import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;
import org.sqlite.Function;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * {@code root_domain(host)}: registrable domain under the ICANN/registry suffix ({@code img.a.com.cn} → {@code a.com.cn},
 * {@code x.github.io} → {@code github.io}). IP literals, single labels and private suffixes ({@code nas.lan}) stay as the
 * whole host; null/blank becomes ''. SQLite has no public suffix list, so the function is registered on every connection.
 */
public class RootDomainFunction extends Function {
    public static final String NAME = "root_domain";

    public static String rootDomain(String host) {
        if (host == null) return "";
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return "";
        String bare = value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
        if (InetAddresses.isInetAddress(bare)) return bare;
        try {
            InternetDomainName name = InternetDomainName.from(value);
            return name.isUnderRegistrySuffix() ? name.topDomainUnderRegistrySuffix().toString() : value;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return value;
        }
    }

    @Override
    protected void xFunc() throws SQLException {
        if (args() != 1) throw new SQLException(NAME + " expects one argument");
        result(rootDomain(value_text(0)));
    }

    /** Hikari opens physical connections through this data source, so each one carries the function. */
    public static class RegisteringDataSource extends SQLiteDataSource {
        @Override
        public SQLiteConnection getConnection(String username, String password) throws SQLException {
            SQLiteConnection conn = super.getConnection(username, password);
            register(conn);
            return conn;
        }
    }

    public static void register(Connection conn) throws SQLException {
        Function.create(conn, NAME, new RootDomainFunction(), 1, Function.FLAG_DETERMINISTIC);
    }
}
