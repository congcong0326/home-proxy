package org.congcong.common.util.geo;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Slf4j
public abstract class V2rayRulesLoader implements DomainRuleLoader {


    protected abstract String getGeolocationUrl();

    public DomainRuleSet load() throws Exception {
        Path cacheFile = resolveCacheFile();
        boolean success = false;

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
                success = true;
            } finally {
                conn.disconnect();
                log.info("Loading config from {} finished", getGeolocationUrl());
            }
        } catch (Exception e) {
            log.warn("Download geolocation rules failed: {}", e.getMessage());
        }

        if (!success) {
            log.info("Falling back to cached geolocation rules at {}", cacheFile.toAbsolutePath());
            if (!Files.exists(cacheFile)) {
                throw new IllegalStateException("No cached geolocation rules found at " + cacheFile.toAbsolutePath());
            }
        }

        try (InputStream in = Files.newInputStream(cacheFile)) {
            return DomainRuleSet.loadFromStream(in);
        }
    }

    private Path resolveCacheFile() throws Exception {
        URL url = new URL(getGeolocationUrl());
        String fileName = Paths.get(url.getPath()).getFileName().toString();
        // Same directory as application working dir
        return Paths.get(System.getProperty("user.dir")).resolve(fileName);
    }


}
