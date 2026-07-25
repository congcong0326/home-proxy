package org.congcong.controlmanager.clickhouse;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.AuthLog;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ClickHouseAuthLogWriter {
    private final ClickHouseJdbcClient client;

    public int ingest(List<AuthLog> logs) {
        if (logs == null || logs.isEmpty()) return 0;
        List<List<Object>> values = logs.stream().map(log -> {
            List<Object> row = new ArrayList<>(11);
            row.add(Timestamp.from(log.getTs() == null ? Instant.now() : log.getTs()));
            row.add(empty(log.getWorkerId()));
            row.add(empty(log.getProxyName()));
            row.add(zero(log.getInboundId()));
            row.add(zero(log.getUserId()));
            row.add(empty(log.getUsername()));
            row.add(empty(log.getClientIp()));
            row.add(log.getClientPort() == null ? 0 : log.getClientPort());
            row.add(log.isSuccess() ? 1 : 0);
            row.add(log.getFailReason());
            row.add(empty(log.getProtocol()));
            return row;
        }).toList();
        client.batchExecute("INSERT INTO default.auth_log (ts,worker_id,proxy_name,inbound_id,user_id,username,client_ip,client_port,success,fail_reason,protocol) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", values);
        return values.size();
    }

    private String empty(String value) { return value == null ? "" : value; }
    private long zero(Long value) { return value == null ? 0L : value; }
}
