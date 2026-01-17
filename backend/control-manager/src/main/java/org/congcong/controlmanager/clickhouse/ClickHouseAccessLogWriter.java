package org.congcong.controlmanager.clickhouse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.AccessLog;
import org.congcong.common.util.geo.GeoIPUtil;
import org.congcong.common.util.geo.GeoLocation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClickHouseAccessLogWriter {

    private final ClickHouseJdbcClient client;

    @Value("${logs.clickhouse.batchSize:3000}")
    private int batchSize;

    @Value("${logs.clickhouse.maxDelayMs:60000}")
    private long maxDelayMs;

    private final ConcurrentLinkedQueue<AccessLog> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bufferSize = new AtomicInteger(0);
    private final ReentrantLock flushLock = new ReentrantLock();
    private volatile long firstBufferedAt = 0L;

    public int ingest(List<AccessLog> logs) {
        if (logs == null || logs.isEmpty()) return 0;
        long now = System.currentTimeMillis();
        for (AccessLog l : logs) {
            parse(l);
            buffer.add(l);
            int size = bufferSize.incrementAndGet();
            if (size == 1) firstBufferedAt = now;
        }
        if (shouldFlush(now)) {
            return flush();
        }
        return 0;
    }

    private void parse(AccessLog accessLog) {
        String originalTargetIP = accessLog.getOriginalTargetIP();
        String clientIp = accessLog.getClientIp();
        Optional<GeoLocation> targetLocation = GeoIPUtil.getInstance().lookup(originalTargetIP == null ? accessLog.getOriginalTargetHost() : originalTargetIP);
        Optional<GeoLocation> clientLocation = GeoIPUtil.getInstance().lookup(clientIp);
        if (targetLocation.isPresent()) {
            GeoLocation geoLocation = targetLocation.get();
            String country = geoLocation.getCountry();
            String city = geoLocation.getCity();
            accessLog.setDstGeoCity(city);
            accessLog.setDstGeoCountry(country);
        }
        if (clientLocation.isPresent()) {
            GeoLocation geoLocation = clientLocation.get();
            String country = geoLocation.getCountry();
            String city = geoLocation.getCity();
            accessLog.setSrcGeoCity(city);
            accessLog.setSrcGeoCountry(country);
        }


    }

    private boolean shouldFlush(long now) {
        if (bufferSize.get() >= Math.max(1, batchSize)) return true;
        if (bufferSize.get() > 0 && (now - firstBufferedAt) >= Math.max(1, maxDelayMs)) return true;
        return false;
    }

    private int flush() {
        if (!flushLock.tryLock()) return 0;
        long startTime = System.currentTimeMillis();
        int size = 0;
        try {
            size = bufferSize.get();
            if (size <= 0) return 0;
            List<List<Object>> params = new ArrayList<>(size);
            AccessLog l;
            while ((l = buffer.poll()) != null) {
                params.add(toParams(l));
            }
            bufferSize.set(0);
            firstBufferedAt = 0L;
            String sql = "INSERT INTO default.access_log (" +
                    "ts, request_id, user_id, username, proxy_name, inbound_id, " +
                    "client_ip, client_port, src_geo_country, src_geo_city, " +
                    "original_target_host, original_target_ip, original_target_port, " +
                    "rewrite_target_host, rewrite_target_port, dst_geo_country, dst_geo_city, " +
                    "inbound_protocol_type, outbound_protocol_type, route_policy_name, route_policy_id, " +
                    "bytes_in, bytes_out, status, error_code, error_msg, " +
                    "request_duration_ms, dns_duration_ms, connect_duration_ms, connect_target_duration_ms" +
                    ") VALUES (" +
                    "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?" +
                    ")";
            client.batchExecute(sql, params);
            return params.size();
        } finally {
            log.info("write {} cost time {} ms", size, System.currentTimeMillis() - startTime);
            flushLock.unlock();
        }
    }

    private List<Object> toParams(AccessLog l) {
        List<Object> p = new ArrayList<>(30);
        p.add(toTimestamp(l.getTs()));
        p.add(l.getRequestId());
        p.add(nullToZero(l.getUserId()));
        p.add(nullToEmpty(l.getUsername()));
        p.add(nullToEmpty(l.getProxyName()));
        p.add(nullToZero(l.getInboundId()));
        p.add(nullToEmpty(l.getClientIp()));
        p.add(nullToZero(l.getClientPort()));
        p.add(nullToEmpty(l.getSrcGeoCountry()));
        p.add(nullToEmpty(l.getSrcGeoCity()));
        p.add(nullToEmpty(l.getOriginalTargetHost()));
        p.add(nullToEmpty(l.getOriginalTargetIP()));
        p.add(nullToZero(l.getOriginalTargetPort()));
        p.add(nullToEmpty(l.getRewriteTargetHost()));
        p.add(nullToZero(l.getRewriteTargetPort()));
        p.add(nullToEmpty(l.getDstGeoCountry()));
        p.add(nullToEmpty(l.getDstGeoCity()));
        p.add(nullToEmpty(l.getInboundProtocolType()));
        p.add(nullToEmpty(l.getOutboundProtocolType()));
        p.add(nullToEmpty(l.getRoutePolicyName()));
        p.add(nullToZero(l.getRoutePolicyId()));
        p.add(nullToZero(l.getBytesIn()));
        p.add(nullToZero(l.getBytesOut()));
        p.add(nullToZero(l.getStatus()));
        p.add(l.getErrorCode());
        p.add(l.getErrorMsg());
        p.add(nullToZero(l.getRequestDurationMs()));
        p.add(nullToZero(l.getDnsDurationMs()));
        p.add(nullToZero(l.getConnectDurationMs()));
        p.add(nullToZero(l.getConnectTargetDurationMs()));
        return p;
    }

    private Timestamp toTimestamp(Instant instant) {
        if (instant == null) return Timestamp.from(Instant.now());
        return Timestamp.from(instant);
    }

    private long nullToZero(Long v) { return v == null ? 0L : v; }
    private int nullToZero(Integer v) { return v == null ? 0 : v; }
    private String nullToEmpty(String s) { return s == null ? "" : s; }
}