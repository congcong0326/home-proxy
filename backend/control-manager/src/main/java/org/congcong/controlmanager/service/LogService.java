package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.AccessLog;
import org.congcong.common.dto.AuthLog;
import org.congcong.controlmanager.dto.AccessLogDetail;
import org.congcong.controlmanager.dto.AccessLogListItem;
import org.congcong.controlmanager.dto.AccessLogQueryRequest;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.entity.AccessLogEntity;
import org.congcong.controlmanager.entity.AuthLogEntity;
import org.congcong.controlmanager.repository.AccessLogRepository;
import org.congcong.controlmanager.repository.AuthLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LogService {

    private final AccessLogRepository accessLogRepository;
    private final AuthLogRepository authLogRepository;

    @Transactional
    public int saveAccessLogs(List<AccessLog> logs) {
        if (logs == null || logs.isEmpty()) return 0;
        List<AccessLogEntity> entities = new ArrayList<>(logs.size());
        for (AccessLog l : logs) {
            AccessLogEntity e = new AccessLogEntity();
            e.setTs(toLocalDateTime(l.getTs()));
            e.setRequestId(l.getRequestId());
            e.setUserId(l.getUserId());
            e.setUsername(l.getUsername());
            e.setProxyName(l.getProxyName());
            e.setInboundId(l.getInboundId());
            e.setClientIp(l.getClientIp());
            e.setClientPort(l.getClientPort());
            e.setSrcGeoCountry(l.getSrcGeoCountry());
            e.setSrcGeoCity(l.getSrcGeoCity());
            e.setOriginalTargetHost(l.getOriginalTargetHost());
            e.setOriginalTargetIP(l.getOriginalTargetIP());
            e.setOriginalTargetPort(l.getOriginalTargetPort());
            e.setRewriteTargetHost(l.getRewriteTargetHost());
            e.setRewriteTargetPort(l.getRewriteTargetPort());
            e.setDstGeoCountry(l.getDstGeoCountry());
            e.setDstGeoCity(l.getDstGeoCity());
            e.setInboundProtocolType(l.getInboundProtocolType());
            e.setOutboundProtocolType(l.getOutboundProtocolType());
            e.setRoutePolicyName(l.getRoutePolicyName());
            e.setRoutePolicyId(l.getRoutePolicyId());
            e.setBytesIn(l.getBytesIn());
            e.setBytesOut(l.getBytesOut());
            e.setStatus(l.getStatus());
            e.setErrorCode(l.getErrorCode());
            e.setErrorMsg(l.getErrorMsg());
            e.setRequestDurationMs(l.getRequestDurationMs());
            e.setDnsDurationMs(l.getDnsDurationMs());
            e.setConnectDurationMs(l.getConnectDurationMs());
            e.setConnectTargetDurationMs(l.getConnectTargetDurationMs());
            entities.add(e);
        }
        accessLogRepository.saveAll(entities);
        return entities.size();
    }

    @Transactional
    public int saveAuthLogs(List<AuthLog> logs) {
        if (logs == null || logs.isEmpty()) return 0;
        List<AuthLogEntity> entities = new ArrayList<>(logs.size());
        for (AuthLog l : logs) {
            AuthLogEntity e = new AuthLogEntity();
            e.setTs(toLocalDateTime(l.getTs()));
            e.setProxyName(l.getProxyName());
            e.setInboundId(l.getInboundId());
            e.setUserId(l.getUserId());
            e.setUsername(l.getUsername());
            e.setClientIp(l.getClientIp());
            e.setClientPort(l.getClientPort());
            e.setSuccess(l.isSuccess());
            e.setFailReason(l.getFailReason());
            e.setProtocol(l.getProtocol());
            entities.add(e);
        }
        authLogRepository.saveAll(entities);
        return entities.size();
    }

    private static LocalDateTime toLocalDateTime(java.time.Instant instant) {
        if (instant == null) return LocalDateTime.now();
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    /**
     * 分页检索访问日志
     */
    public PageResponse<AccessLogListItem> queryAccessLogs(AccessLogQueryRequest req) {
        Specification<AccessLogEntity> spec = buildAccessLogSpec(req);

        Sort sort = parseSort(req.getSort());
        int page = req.getPage() == null ? 0 : Math.max(req.getPage(), 0);
        int size = req.getSize() == null ? 20 : Math.min(Math.max(req.getSize(), 1), 100);
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<AccessLogEntity> result = accessLogRepository.findAll(spec, pageable);
        List<AccessLogListItem> items = result.getContent().stream().map(this::toListItem).collect(Collectors.toList());
        return new PageResponse<>(items, page + 1, size, result.getTotalElements());
    }

    /**
     * 构建访问日志过滤条件 Specification
     */
    private Specification<AccessLogEntity> buildAccessLogSpec(AccessLogQueryRequest req) {
        Specification<AccessLogEntity> spec = (root, query, cb) -> cb.conjunction();

        LocalDateTime from = parseDate(req.getFrom());
        LocalDateTime to = parseDate(req.getTo());
        if (from != null) {
            spec = spec.and((root, cq, cb) -> cb.greaterThanOrEqualTo(root.get("ts"), from));
        }
        if (to != null) {
            spec = spec.and((root, cq, cb) -> cb.lessThanOrEqualTo(root.get("ts"), to));
        }
        if (req.getUserId() != null) spec = spec.and((r, cq, cb) -> cb.equal(r.get("userId"), req.getUserId()));
        if (notBlank(req.getUsername())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("username"), req.getUsername()));
        if (notBlank(req.getProxyName())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("proxyName"), req.getProxyName()));
        if (req.getInboundId() != null) spec = spec.and((r, cq, cb) -> cb.equal(r.get("inboundId"), req.getInboundId()));
        if (notBlank(req.getClientIp())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("clientIp"), req.getClientIp()));
        if (req.getStatus() != null) spec = spec.and((r, cq, cb) -> cb.equal(r.get("status"), req.getStatus()));
        if (notBlank(req.getProtocol())) {
            if ("inbound".equalsIgnoreCase(req.getProtocol())) {
                spec = spec.and((r, cq, cb) -> cb.isNotNull(r.get("inboundProtocolType")));
            } else if ("outbound".equalsIgnoreCase(req.getProtocol())) {
                spec = spec.and((r, cq, cb) -> cb.isNotNull(r.get("outboundProtocolType")));
            }
        }
        if (req.getRoutePolicyId() != null) spec = spec.and((r, cq, cb) -> cb.equal(r.get("routePolicyId"), req.getRoutePolicyId()));
        if (notBlank(req.getSrcGeoCountry())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("srcGeoCountry"), req.getSrcGeoCountry()));
        if (notBlank(req.getSrcGeoCity())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("srcGeoCity"), req.getSrcGeoCity()));
        if (notBlank(req.getDstGeoCountry())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("dstGeoCountry"), req.getDstGeoCountry()));
        if (notBlank(req.getDstGeoCity())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("dstGeoCity"), req.getDstGeoCity()));
        if (notBlank(req.getHost())) {
            spec = spec.and((r, cq, cb) -> cb.or(
                    cb.equal(r.get("originalTargetHost"), req.getHost()),
                    cb.equal(r.get("rewriteTargetHost"), req.getHost())
            ));
        }
        if (notBlank(req.getOriginalTargetHost())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("originalTargetHost"), req.getOriginalTargetHost()));
        if (notBlank(req.getRewriteTargetHost())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("rewriteTargetHost"), req.getRewriteTargetHost()));
        if (notBlank(req.getQ())) {
            String like = "%" + req.getQ().trim() + "%";
            spec = spec.and((r, cq, cb) -> cb.or(
                    cb.like(r.get("clientIp"), like),
                    cb.like(r.get("username"), like),
                    cb.like(r.get("proxyName"), like),
                    cb.like(r.get("originalTargetHost"), like),
                    cb.like(r.get("rewriteTargetHost"), like),
                    cb.like(r.get("errorMsg"), like)
            ));
        }
        return spec;
    }

    /**
     * 时间序列聚合
     */
    public List<org.congcong.controlmanager.dto.TimeSeriesPoint> aggregateAccessTimeSeries(AccessLogQueryRequest req, String interval, String metric) {
        Specification<AccessLogEntity> spec = buildAccessLogSpec(req);
        List<AccessLogEntity> logs = accessLogRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "ts"));

        java.util.Map<java.time.LocalDateTime, Long> bucketValues = new java.util.HashMap<>();
        for (AccessLogEntity e : logs) {
            LocalDateTime bucket = floorTs(e.getTs(), interval);
            long inc = metricValue(e, metric);
            bucketValues.merge(bucket, inc, Long::sum);
        }

        // 填充空桶
        LocalDateTime from = parseDate(req.getFrom());
        LocalDateTime to = parseDate(req.getTo());
        if (from != null && to != null) {
            LocalDateTime cur = floorTs(from, interval);
            while (!cur.isAfter(to)) {
                bucketValues.putIfAbsent(cur, 0L);
                cur = nextBucket(cur, interval);
            }
        }

        return bucketValues.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(en -> new org.congcong.controlmanager.dto.TimeSeriesPoint(en.getKey(), en.getValue()))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * TopN 聚合
     */
    public List<org.congcong.controlmanager.dto.TopItem> aggregateAccessTop(AccessLogQueryRequest req, String dimension, String metric, int limit) {
        Specification<AccessLogEntity> spec = buildAccessLogSpec(req);
        List<AccessLogEntity> logs = accessLogRepository.findAll(spec);

        java.util.Map<String, Long> map = new java.util.HashMap<>();
        for (AccessLogEntity e : logs) {
            String key = topKey(e, dimension);
            if (key == null || key.isBlank()) continue;
            long inc = metricValue(e, metric);
            map.merge(key, inc, Long::sum);
        }

        return map.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(en -> new org.congcong.controlmanager.dto.TopItem(en.getKey(), en.getValue()))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 分布统计
     */
    public List<org.congcong.controlmanager.dto.DistributionBucket> aggregateAccessDistribution(AccessLogQueryRequest req, String field) {
        Specification<AccessLogEntity> spec = buildAccessLogSpec(req);
        List<AccessLogEntity> logs = accessLogRepository.findAll(spec);

        java.util.Map<String, Long> map = new java.util.HashMap<>();
        for (AccessLogEntity e : logs) {
            String key;
            switch (field == null ? "" : field) {
                case "status":
                    key = e.getStatus() == null ? "unknown" : String.valueOf(e.getStatus());
                    break;
                case "protocol":
                    String proto = e.getInboundProtocolType();
                    if (proto == null || proto.isBlank()) proto = e.getOutboundProtocolType();
                    key = (proto == null || proto.isBlank()) ? "unknown" : proto;
                    break;
                case "sniff_proto":
                    // 使用出站协议类型作为嗅探结果近似
                    String sniff = e.getOutboundProtocolType();
                    key = (sniff == null || sniff.isBlank()) ? "unknown" : sniff;
                    break;
                case "latency_bucket":
                    key = latencyBucket(e.getRequestDurationMs());
                    break;
                default:
                    key = "unknown";
            }
            map.merge(key, 1L, Long::sum);
        }

        return map.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(en -> new org.congcong.controlmanager.dto.DistributionBucket(en.getKey(), en.getValue()))
                .collect(java.util.stream.Collectors.toList());
    }

    private long metricValue(AccessLogEntity e, String metric) {
        switch (metric == null ? "requests" : metric) {
            case "bytes_in":
                return e.getBytesIn() == null ? 0L : e.getBytesIn();
            case "bytes_out":
                return e.getBytesOut() == null ? 0L : e.getBytesOut();
            case "errors":
                Integer st = e.getStatus();
                return (st != null && st >= 500) ? 1L : 0L;
            case "requests":
            default:
                return 1L;
        }
    }

    private String topKey(AccessLogEntity e, String dimension) {
        switch (dimension == null ? "users" : dimension) {
            case "users":
                return e.getUsername();
            case "apps": // apps(host)
            case "apps(host)":
                String host = e.getRewriteTargetHost();
                if (host == null || host.isBlank()) host = e.getOriginalTargetHost();
                return host;
            case "src_geo_country":
                return e.getSrcGeoCountry();
            case "dst_geo_country":
                return e.getDstGeoCountry();
            case "route_policy":
                return e.getRoutePolicyName();
            case "client_ip":
                return e.getClientIp();
            default:
                return null;
        }
    }

    private LocalDateTime floorTs(LocalDateTime ts, String interval) {
        if (ts == null) return null;
        String iv = interval == null ? "5m" : interval;
        switch (iv) {
            case "1m":
                return ts.withSecond(0).withNano(0);
            case "5m":
                int m = ts.getMinute();
                int bucket = (m / 5) * 5;
                return ts.withMinute(bucket).withSecond(0).withNano(0);
            case "1h":
                return ts.withMinute(0).withSecond(0).withNano(0);
            case "1d":
                return ts.withHour(0).withMinute(0).withSecond(0).withNano(0);
            default:
                return ts.withSecond(0).withNano(0);
        }
    }

    private LocalDateTime nextBucket(LocalDateTime ts, String interval) {
        String iv = interval == null ? "5m" : interval;
        switch (iv) {
            case "1m":
                return ts.plusMinutes(1);
            case "5m":
                return ts.plusMinutes(5);
            case "1h":
                return ts.plusHours(1);
            case "1d":
                return ts.plusDays(1);
            default:
                return ts.plusMinutes(5);
        }
    }

    private String latencyBucket(Long ms) {
        if (ms == null) return "unknown";
        long v = ms;
        if (v < 10) return "0-10ms";
        if (v < 50) return "10-50ms";
        if (v < 100) return "50-100ms";
        if (v < 200) return "100-200ms";
        if (v < 500) return "200-500ms";
        if (v < 1000) return "500-1000ms";
        if (v < 2000) return "1000-2000ms";
        if (v < 5000) return "2000-5000ms";
        return ">=5000ms";
    }

    /**
     * 访问日志详情
     */
    public Optional<AccessLogDetail> getAccessLogDetail(Long id) {
        return accessLogRepository.findById(id).map(this::toDetail);
    }

    private AccessLogListItem toListItem(AccessLogEntity e) {
        return new AccessLogListItem(
                e.getId(), e.getTs(), e.getRequestId(), e.getUserId(), e.getUsername(), e.getProxyName(),
                e.getInboundId(), e.getClientIp(), e.getStatus(), e.getBytesIn(), e.getBytesOut(), e.getRequestDurationMs(),
                e.getOriginalTargetHost(), e.getRewriteTargetHost()
        );
    }

    private AccessLogDetail toDetail(AccessLogEntity e) {
        return new AccessLogDetail(
                e.getId(), e.getTs(), e.getRequestId(), e.getUserId(), e.getUsername(), e.getProxyName(), e.getInboundId(),
                e.getClientIp(), e.getClientPort(), e.getSrcGeoCountry(), e.getSrcGeoCity(), e.getOriginalTargetHost(), e.getOriginalTargetIP(),
                e.getOriginalTargetPort(), e.getRewriteTargetHost(), e.getRewriteTargetPort(), e.getDstGeoCountry(), e.getDstGeoCity(),
                e.getInboundProtocolType(), e.getOutboundProtocolType(), e.getRoutePolicyName(), e.getRoutePolicyId(), e.getBytesIn(), e.getBytesOut(),
                e.getStatus(), e.getErrorCode(), e.getErrorMsg(), e.getRequestDurationMs(), e.getDnsDurationMs(), e.getConnectDurationMs(), e.getConnectTargetDurationMs()
        );
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) return Sort.by(Sort.Direction.DESC, "ts");
        String[] parts = sort.split(",");
        if (parts.length == 2) {
            Sort.Direction dir = "asc".equalsIgnoreCase(parts[1]) ? Sort.Direction.ASC : Sort.Direction.DESC;
            return Sort.by(dir, parts[0]);
        }
        return Sort.by(Sort.Direction.DESC, sort);
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private LocalDateTime parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.matches("^\\d{13}$")) { // 毫秒时间戳
                long ms = Long.parseLong(s);
                return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault());
            }
            java.time.Instant instant = java.time.Instant.parse(s);
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        } catch (DateTimeParseException | NumberFormatException ex) {
            return null;
        }
    }
}