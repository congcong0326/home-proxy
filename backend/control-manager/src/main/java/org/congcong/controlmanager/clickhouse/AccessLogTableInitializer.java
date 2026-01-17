package org.congcong.controlmanager.clickhouse;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "logs.persistence", havingValue = "clickhouse")
public class AccessLogTableInitializer implements ApplicationRunner {
    private final ClickHouseJdbcClient client;

    @Override
    public void run(ApplicationArguments args) {
        String createTable = """
                CREATE TABLE IF NOT EXISTS default.access_log
                (
                    ts                         DateTime64(3, 'UTC'),
                    request_id                 String,
                    user_id                    UInt64,
                    username                   LowCardinality(String),
                    proxy_name                 LowCardinality(String),
                    inbound_id                 UInt64,

                    client_ip                  String,
                    client_port                UInt16,
                    src_geo_country            LowCardinality(String),
                    src_geo_city               LowCardinality(String),

                    original_target_host       LowCardinality(String),
                    original_target_ip         String,
                    original_target_port       UInt16,
                    rewrite_target_host        LowCardinality(String),
                    rewrite_target_port        UInt16,
                    dst_geo_country            LowCardinality(String),
                    dst_geo_city               LowCardinality(String),

                    inbound_protocol_type      LowCardinality(String),
                    outbound_protocol_type     LowCardinality(String),
                    route_policy_name          LowCardinality(String),
                    route_policy_id            UInt64,

                    bytes_in                   UInt64,
                    bytes_out                  UInt64,
                    status                     Int32,
                    error_code                 LowCardinality(String),
                    error_msg                  Nullable(String),

                    dns_answer_ips             Array(String),
                    request_duration_ms        UInt32,
                    dns_duration_ms            UInt32,
                    connect_duration_ms        UInt32,
                    connect_target_duration_ms UInt32
                )
                ENGINE = MergeTree
                PARTITION BY toYYYYMM(ts)
                ORDER BY (ts, request_id, inbound_id, user_id, route_policy_id)
                SETTINGS index_granularity = 8192
                """;
        client.execute(createTable);
        client.execute("ALTER TABLE default.access_log ADD COLUMN IF NOT EXISTS dns_answer_ips Array(String)");
    }
}
