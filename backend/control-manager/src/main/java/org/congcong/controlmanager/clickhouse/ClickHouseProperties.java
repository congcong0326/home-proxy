package org.congcong.controlmanager.clickhouse;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "clickhouse")
public class ClickHouseProperties {
    private String url;
    private String username;
    private String password;
    private Integer poolSize = 10;
    private Long connectionTimeoutMs = 5000L;
    private Long maxLifetimeMs = 300000L;
    private Long idleTimeoutMs = 60000L;
}