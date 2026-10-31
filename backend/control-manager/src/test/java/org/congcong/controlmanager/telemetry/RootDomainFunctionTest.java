package org.congcong.controlmanager.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RootDomainFunctionTest {
    @Test
    void registrableDomainKeepsIpsAndPrivateHosts() {
        assertEquals("google.com", RootDomainFunction.rootDomain("www.Google.com."));
        assertEquals("example.com.cn", RootDomainFunction.rootDomain("img.cdn.example.com.cn"));
        assertEquals("bbc.co.uk", RootDomainFunction.rootDomain("news.bbc.co.uk"));
        assertEquals("github.io", RootDomainFunction.rootDomain("someone.github.io"));
        assertEquals("example.com", RootDomainFunction.rootDomain("example.com"));
        assertEquals("1.2.3.4", RootDomainFunction.rootDomain("1.2.3.4"));
        assertEquals("2001:db8::1", RootDomainFunction.rootDomain("[2001:DB8::1]"));
        assertEquals("nas.lan", RootDomainFunction.rootDomain("nas.lan"));
        assertEquals("localhost", RootDomainFunction.rootDomain("localhost"));
        assertEquals("com", RootDomainFunction.rootDomain("com"));
        assertEquals("", RootDomainFunction.rootDomain(null));
        assertEquals("", RootDomainFunction.rootDomain(" "));
        assertEquals("bad_host!", RootDomainFunction.rootDomain("bad_host!"));
    }

    @Test
    void pooledTelemetryConnectionsCanGroupByRootDomain(@TempDir Path dir) {
        var dataSource = new TelemetryDataSourceConfig().telemetryDataSource("jdbc:sqlite:" + dir.resolve("t.db"), 1000, 2);
        try {
            var client = new TelemetryJdbcClient(dataSource);
            client.execute("CREATE TABLE s (host TEXT)");
            for (String host : new String[]{"a.example.com", "b.example.com", "8.8.8.8", null}) client.execute("INSERT INTO s VALUES (?)", host);
            List<Map<String, Object>> rows = client.query(
                    "SELECT root_domain(host) AS d, COUNT(*) AS c FROM s GROUP BY d ORDER BY c DESC, d");
            assertEquals(List.of("example.com", "", "8.8.8.8"), rows.stream().map(r -> r.get("d")).toList());
            assertEquals(2, ((Number) rows.get(0).get("c")).intValue());
        } finally {
            ((com.zaxxer.hikari.HikariDataSource) dataSource).close();
        }
    }
}
