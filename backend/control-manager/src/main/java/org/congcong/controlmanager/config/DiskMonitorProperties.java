package org.congcong.controlmanager.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "disk.monitor")
public class DiskMonitorProperties {

    private String pushToken = "";

    private int retentionDays = 7;
}
