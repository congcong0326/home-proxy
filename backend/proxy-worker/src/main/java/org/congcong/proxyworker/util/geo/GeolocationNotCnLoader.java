package org.congcong.proxyworker.util.geo;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@Slf4j
public class GeolocationNotCnLoader {

    private static final String GEOLOCATION_NOT_CN_URL =
            "https://raw.githubusercontent.com/v2fly/domain-list-community/master/data/geolocation-!cn";

    public static DomainRuleSet load() throws Exception {
        log.info("Loading Geolocation Not Cn...");
        HttpURLConnection conn = (HttpURLConnection) new URL(GEOLOCATION_NOT_CN_URL).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("GET");

        try (InputStream in = conn.getInputStream()) {
            return DomainRuleSet.loadFromStream(in);
        } finally {
            log.info("Geolocation Not Cn loaded");
        }
    }
}
