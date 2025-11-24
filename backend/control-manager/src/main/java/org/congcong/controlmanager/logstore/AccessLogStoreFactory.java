package org.congcong.controlmanager.logstore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccessLogStoreFactory {
    private final ClickHouseAccessLogStore clickHouseAccessLogStore;


    public AccessLogStore current() {
        return clickHouseAccessLogStore;
    }

}