package org.congcong.common.util.geo;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

@Slf4j
public class AdBlockRuleLoader extends V2rayRulesLoader {
    @Override
    protected String getGeolocationUrl() {
        return "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/reject-list.txt";
    }
}