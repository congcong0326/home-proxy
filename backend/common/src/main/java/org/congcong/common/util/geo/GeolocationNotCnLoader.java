package org.congcong.common.util.geo;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@Slf4j
public class GeolocationNotCnLoader extends GeolocationLoader {

    @Override
    protected String getGeolocationUrl() {
        return "https://raw.githubusercontent.com/v2fly/domain-list-community/refs/heads/release/geolocation-!cn.txt";
    }
}
