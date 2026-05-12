package org.congcong.common.util.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoIpDatabaseLocatorTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitSystemPropertyPathHasHighestPriority() throws IOException {
        Path configured = createFile("configured.mmdb");
        Path envConfigured = createFile("env-configured.mmdb");
        Properties properties = new Properties();
        properties.setProperty("geoip.mmdb.path", configured.toString());

        Optional<Path> resolved = GeoIpDatabaseLocator.resolve(
                properties,
                Map.of("GEOIP_MMDB_PATH", envConfigured.toString()),
                tempDir.resolve("work"),
                tempDir.resolve("app"));

        assertEquals(configured.toAbsolutePath().normalize(), resolved.orElseThrow());
    }

    @Test
    void explicitEnvironmentPathIsUsedWhenSystemPropertyIsMissing() throws IOException {
        Path configured = createFile("env-configured.mmdb");

        Optional<Path> resolved = GeoIpDatabaseLocator.resolve(
                new Properties(),
                Map.of("GEOIP_MMDB_PATH", configured.toString()),
                tempDir.resolve("work"),
                tempDir.resolve("app"));

        assertEquals(configured.toAbsolutePath().normalize(), resolved.orElseThrow());
    }

    @Test
    void configuredDataDirectoryIsCheckedBeforeImageDefaults() throws IOException {
        Path dataDir = tempDir.resolve("custom-data");
        Path configured = createMmdb(dataDir);
        Path appDefault = createMmdb(tempDir.resolve("app").resolve("data/geoip"));
        Properties properties = new Properties();
        properties.setProperty("geoip.data.dir", dataDir.toString());

        Optional<Path> resolved = GeoIpDatabaseLocator.resolve(
                properties,
                Map.of(),
                tempDir.resolve("work"),
                tempDir.resolve("app"));

        assertEquals(configured.toAbsolutePath().normalize(), resolved.orElseThrow());
        assertTrue(Files.exists(appDefault));
    }

    @Test
    void containerImageDataPathIsUsedBeforeWorkingDirectoryDataPath() throws IOException {
        Path appDir = tempDir.resolve("app");
        Path appDefault = createMmdb(appDir.resolve("data/geoip"));
        Path workDefault = createMmdb(tempDir.resolve("work").resolve("data/geoip"));

        Optional<Path> resolved = GeoIpDatabaseLocator.resolve(
                new Properties(),
                Map.of(),
                tempDir.resolve("work"),
                appDir);

        assertEquals(appDefault.toAbsolutePath().normalize(), resolved.orElseThrow());
        assertTrue(Files.exists(workDefault));
    }

    @Test
    void workingDirectoryDataPathIsUsedWhenImageDefaultIsMissing() throws IOException {
        Path workDir = tempDir.resolve("work");
        Path workDefault = createMmdb(workDir.resolve("data/geoip"));

        Optional<Path> resolved = GeoIpDatabaseLocator.resolve(
                new Properties(),
                Map.of(),
                workDir,
                tempDir.resolve("app"));

        assertEquals(workDefault.toAbsolutePath().normalize(), resolved.orElseThrow());
    }

    @Test
    void legacyWorkingDirectoryMmdbScanIsLastFallback() throws IOException {
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);
        Path legacy = Files.createFile(workDir.resolve("legacy-city.mmdb"));

        Optional<Path> resolved = GeoIpDatabaseLocator.resolve(
                new Properties(),
                Map.of(),
                workDir,
                tempDir.resolve("app"));

        assertEquals(legacy.toAbsolutePath().normalize(), resolved.orElseThrow());
    }

    @Test
    void missingDatabaseReturnsEmpty() {
        Optional<Path> resolved = GeoIpDatabaseLocator.resolve(
                new Properties(),
                Map.of(),
                tempDir.resolve("work"),
                tempDir.resolve("app"));

        assertTrue(resolved.isEmpty());
    }

    private Path createFile(String name) throws IOException {
        return Files.createFile(tempDir.resolve(name));
    }

    private Path createMmdb(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        return Files.createFile(dataDir.resolve("GeoLite2-City.mmdb"));
    }
}
