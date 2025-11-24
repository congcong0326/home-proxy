package org.congcong.controlmanager.clickhouse;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "logs.persistence", havingValue = "clickhouse")
@EnableConfigurationProperties(ClickHouseProperties.class)
public class ClickHouseConfig {
    @Bean(name = "clickHouseDataSource")
    public DataSource clickHouseDataSource(ClickHouseProperties props) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(props.getUrl());
        cfg.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        if (props.getUsername() != null) {
            cfg.setUsername(props.getUsername());
        }
        if (props.getPassword() != null) {
            cfg.setPassword(props.getPassword());
        }
        if (props.getPoolSize() != null) {
            cfg.setMaximumPoolSize(props.getPoolSize());
        }
        if (props.getConnectionTimeoutMs() != null) {
            cfg.setConnectionTimeout(props.getConnectionTimeoutMs());
        }
        if (props.getMaxLifetimeMs() != null) {
            cfg.setMaxLifetime(props.getMaxLifetimeMs());
        }
        if (props.getIdleTimeoutMs() != null) {
            cfg.setIdleTimeout(props.getIdleTimeoutMs());
        }
        return new HikariDataSource(cfg);
    }
}