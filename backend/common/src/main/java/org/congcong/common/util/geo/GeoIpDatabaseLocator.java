package org.congcong.common.util.geo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

final class GeoIpDatabaseLocator {
    static final String DATABASE_FILE_NAME = "GeoLite2-City.mmdb";
    private static final String PROPERTY_MMDB_PATH = "geoip.mmdb.path";
    private static final String PROPERTY_DATA_DIR = "geoip.data.dir";
    private static final String ENV_MMDB_PATH = "GEOIP_MMDB_PATH";
    private static final String ENV_DATA_DIR = "GEOIP_DATA_DIR";
    private static final String DATA_DIR = "data/geoip";

    private GeoIpDatabaseLocator() {
    }

    static Optional<Path> resolveDefault() {
        return resolve(
                System.getProperties(),
                System.getenv(),
                Paths.get(System.getProperty("user.dir")),
                Paths.get("/app"));
    }

    static Optional<Path> resolve(Properties properties, Map<String, String> env, Path workDir, Path appDir) {
        Set<Path> candidates = new LinkedHashSet<>();

        addExplicitPath(candidates, properties.getProperty(PROPERTY_MMDB_PATH));
        addExplicitPath(candidates, env.get(ENV_MMDB_PATH));
        addDataDir(candidates, properties.getProperty(PROPERTY_DATA_DIR));
        addDataDir(candidates, env.get(ENV_DATA_DIR));
        addDataDir(candidates, appDir.resolve(DATA_DIR).toString());
        addDataDir(candidates, workDir.resolve(DATA_DIR).toString());

        for (Path candidate : candidates) {
            Path normalized = normalize(candidate);
            if (Files.isRegularFile(normalized)) {
                return Optional.of(normalized);
            }
        }

        return findLegacyMmdb(workDir);
    }

    private static void addExplicitPath(Set<Path> candidates, String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            candidates.add(Paths.get(configuredPath.trim()));
        }
    }

    private static void addDataDir(Set<Path> candidates, String configuredDir) {
        if (configuredDir != null && !configuredDir.isBlank()) {
            candidates.add(Paths.get(configuredDir.trim()).resolve(DATABASE_FILE_NAME));
        }
    }

    private static Optional<Path> findLegacyMmdb(Path workDir) {
        Path normalizedWorkDir = normalize(workDir);
        if (!Files.isDirectory(normalizedWorkDir)) {
            return Optional.empty();
        }

        try (Stream<Path> files = Files.list(normalizedWorkDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(GeoIpDatabaseLocator::isMmdb)
                    .map(GeoIpDatabaseLocator::normalize)
                    .sorted(Comparator.comparing(Path::toString))
                    .findFirst();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static boolean isMmdb(Path path) {
        return path.getFileName().toString().toLowerCase().contains("mmdb");
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
