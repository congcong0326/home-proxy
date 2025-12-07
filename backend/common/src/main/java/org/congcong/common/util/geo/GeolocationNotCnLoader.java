package org.congcong.common.util.geo;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GeolocationNotCnLoader extends V2rayRulesLoader {

    @Override
    protected String getGeolocationUrl() {
        return "https://raw.githubusercontent.com/v2fly/domain-list-community/refs/heads/release/geolocation-!cn.txt";
    }
}
