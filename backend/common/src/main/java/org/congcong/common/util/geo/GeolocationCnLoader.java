package org.congcong.common.util.geo;

public class GeolocationCnLoader extends GeolocationLoader {

    @Override
    protected String getGeolocationUrl() {
        return "https://raw.githubusercontent.com/v2fly/domain-list-community/refs/heads/release/cn.txt";
    }
}
