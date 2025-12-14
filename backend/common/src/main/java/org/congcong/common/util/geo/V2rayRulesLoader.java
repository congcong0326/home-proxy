package org.congcong.common.util.geo;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class V2rayRulesLoader implements DomainRuleLoader {

    private final AtomicBoolean cachePreferred = new AtomicBoolean(true);

    protected abstract String getGeolocationUrl();

    public DomainRuleSet load() throws Exception {
        Path cacheFile = resolveCacheFile();
        boolean preferCache = cachePreferred.getAndSet(false);

        if (preferCache) {
            DomainRuleSet cached = tryLoadFromCache(cacheFile);
            if (cached != null) {
                return cached;
            }
            log.info("No cached geolocation rules at {}, downloading from {}", cacheFile.toAbsolutePath(), getGeolocationUrl());
            DomainRuleSet downloaded = downloadAndLoad(cacheFile);
            if (downloaded != null) {
                return downloaded;
            }
            throw new IllegalStateException("Unable to load geolocation rules from cache or remote; cache path: " + cacheFile.toAbsolutePath());
        }

        DomainRuleSet downloaded = downloadAndLoad(cacheFile);
        if (downloaded != null) {
            return downloaded;
        }
        DomainRuleSet cached = tryLoadFromCache(cacheFile);
        if (cached != null) {
            return cached;
        }
        throw new IllegalStateException("Unable to load geolocation rules from cache or remote; cache path: " + cacheFile.toAbsolutePath());
    }

    private DomainRuleSet tryLoadFromCache(Path cacheFile) {
        if (!Files.exists(cacheFile)) {
            return null;
        }

        log.info("Loading geolocation rules from cache {}", cacheFile.toAbsolutePath());
        try (InputStream in = Files.newInputStream(cacheFile)) {
            return DomainRuleSet.loadFromStream(in);
        } catch (Exception e) {
            log.warn("Failed to load cached geolocation rules at {}: {}", cacheFile.toAbsolutePath(), e.getMessage());
            return null;
        }
    }

    private DomainRuleSet downloadAndLoad(Path cacheFile) {
        try {
            log.info("Loading config from {}", getGeolocationUrl());
            HttpURLConnection conn = (HttpURLConnection) new URL(getGeolocationUrl()).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");

            Path tempFile = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");

            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tempFile, cacheFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                log.info("Saved geolocation rules to {}", cacheFile.toAbsolutePath());
            } finally {
                conn.disconnect();
                log.info("Loading config from {} finished", getGeolocationUrl());
            }

            try (InputStream in = Files.newInputStream(cacheFile)) {
                return DomainRuleSet.loadFromStream(in);
            }
        } catch (Exception e) {
            log.warn("Download geolocation rules failed: {}", e.getMessage());
            return null;
        }
    }

    private Path resolveCacheFile() throws Exception {
        URL url = new URL(getGeolocationUrl());
        String fileName = Paths.get(url.getPath()).getFileName().toString();
        // Same directory as application working dir
        return Paths.get(System.getProperty("user.dir")).resolve(fileName);
    }


}
