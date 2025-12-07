package org.congcong.common.util.geo;

public class GeolocationCnLoader extends V2rayRulesLoader {

    @Override
    protected String getGeolocationUrl() {
        return "https://raw.githubusercontent.com/v2fly/domain-list-community/refs/heads/release/cn.txt";
    }
}
