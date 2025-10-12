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
import org.congcong.controlmanager.repository.agg.MonthlyAppStatsRepository;
import org.congcong.controlmanager.repository.agg.MonthlyUserStatsRepository;
import org.congcong.controlmanager.repository.agg.MonthlyUserAppStatsRepository;
import org.congcong.controlmanager.repository.agg.MonthlySrcGeoStatsRepository;
import org.congcong.controlmanager.repository.agg.MonthlyDstGeoStatsRepository;
import org.congcong.controlmanager.repository.agg.DailyUserStatsRepository;
import org.congcong.controlmanager.repository.agg.DailyAppStatsRepository;
import org.congcong.controlmanager.repository.agg.DailyUserAppStatsRepository;
import org.congcong.controlmanager.repository.agg.DailySrcGeoStatsRepository;
import org.congcong.controlmanager.repository.agg.DailyDstGeoStatsRepository;
import org.congcong.controlmanager.entity.agg.MonthlyAppStats;
import org.congcong.controlmanager.entity.agg.MonthlyUserStats;
import org.congcong.controlmanager.entity.agg.MonthlyUserAppStats;
import org.congcong.controlmanager.entity.agg.MonthlySrcGeoStats;
import org.congcong.controlmanager.entity.agg.MonthlyDstGeoStats;
import org.congcong.controlmanager.entity.agg.DailyAppStats;
import org.congcong.controlmanager.entity.agg.DailyUserStats;
import org.congcong.controlmanager.entity.agg.DailyUserAppStats;
import org.congcong.controlmanager.entity.agg.DailySrcGeoStats;
import org.congcong.controlmanager.entity.agg.DailyDstGeoStats;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

@Service
@RequiredArgsConstructor
public class LogService {

    private final AccessLogRepository accessLogRepository;
    private final AuthLogRepository authLogRepository;
    private final MonthlyUserStatsRepository monthlyUserStatsRepository;
    private final MonthlyAppStatsRepository monthlyAppStatsRepository;
    private final MonthlyUserAppStatsRepository monthlyUserAppStatsRepository;
    private final MonthlySrcGeoStatsRepository monthlySrcGeoStatsRepository;
    private final MonthlyDstGeoStatsRepository monthlyDstGeoStatsRepository;
    private final DailyUserStatsRepository dailyUserStatsRepository;
    private final DailyAppStatsRepository dailyAppStatsRepository;
    private final DailyUserAppStatsRepository dailyUserAppStatsRepository;
    private final DailySrcGeoStatsRepository dailySrcGeoStatsRepository;
    private final DailyDstGeoStatsRepository dailyDstGeoStatsRepository;

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
        // 增量更新月度聚合统计
        //updateMonthlyAggregates(logs);
        // 增量更新日度聚合统计
        updateDailyAggregates(logs);
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
     * 基于月度聚合表的 TopN 查询
     */
    public List<org.congcong.controlmanager.dto.TopItem> aggregateMonthlyTop(String month, String dimension, String metric, int limit) {
        LocalDate monthDate = parseMonth(month);
        int topN = limit <= 0 ? 10 : Math.min(limit, 100);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, topN);

        String dim = dimension == null ? "users" : dimension;
        String met = metric == null ? "requests" : metric;
        java.util.List<org.congcong.controlmanager.dto.TopItem> result = new java.util.ArrayList<>();

        switch (dim) {
            case "users": {
                java.util.List<MonthlyUserStats> list = monthlyUserStatsRepository.findTopByMonth(monthDate, met.equals("bytes") ? "bytes" : "requests", pageable);
                for (MonthlyUserStats m : list) {
                    long val = met.equals("bytes") ? (nullSafe(m.getBytesIn()) + nullSafe(m.getBytesOut())) : nullSafe(m.getRequestsCount());
                    String key = (m.getUsername() != null && !m.getUsername().isBlank()) ? m.getUsername() : String.valueOf(m.getUserId());
                    result.add(new org.congcong.controlmanager.dto.TopItem(key, val));
                }
                break;
            }
            case "apps": {
                java.util.List<MonthlyAppStats> list = monthlyAppStatsRepository.findTopByMonth(monthDate, met.equals("bytes") ? "bytes" : "requests", pageable);
                for (MonthlyAppStats m : list) {
                    long val = met.equals("bytes") ? (nullSafe(m.getBytesIn()) + nullSafe(m.getBytesOut())) : nullSafe(m.getRequestsCount());
                    String key = m.getTargetHost();
                    result.add(new org.congcong.controlmanager.dto.TopItem(key, val));
                }
                break;
            }
            case "user_apps": {
                java.util.List<MonthlyUserAppStats> list = monthlyUserAppStatsRepository.findTopByMonth(monthDate, met.equals("bytes") ? "bytes" : "requests", pageable);
                for (MonthlyUserAppStats m : list) {
                    long val = met.equals("bytes") ? (nullSafe(m.getBytesIn()) + nullSafe(m.getBytesOut())) : nullSafe(m.getRequestsCount());
                    String uname = (m.getUsername() != null && !m.getUsername().isBlank()) ? m.getUsername() : String.valueOf(m.getUserId());
                    String key = uname + "@" + m.getTargetHost();
                    result.add(new org.congcong.controlmanager.dto.TopItem(key, val));
                }
                break;
            }
            case "src_geo": {
                java.util.List<MonthlySrcGeoStats> list = monthlySrcGeoStatsRepository.findTopByMonth(monthDate, pageable);
                for (MonthlySrcGeoStats m : list) {
                    String country = m.getSrcGeoCountry();
                    String city = m.getSrcGeoCity();
                    String key = (city == null || city.isBlank()) ? (country == null ? "unknown" : country) : (country == null ? city : country + "/" + city);
                    result.add(new org.congcong.controlmanager.dto.TopItem(key, nullSafe(m.getRequestsCount())));
                }
                break;
            }
            case "dst_geo": {
                java.util.List<MonthlyDstGeoStats> list = monthlyDstGeoStatsRepository.findTopByMonth(monthDate, pageable);
                for (MonthlyDstGeoStats m : list) {
                    String country = m.getDstGeoCountry();
                    String city = m.getDstGeoCity();
                    String key = (city == null || city.isBlank()) ? (country == null ? "unknown" : country) : (country == null ? city : country + "/" + city);
                    result.add(new org.congcong.controlmanager.dto.TopItem(key, nullSafe(m.getRequestsCount())));
                }
                break;
            }
            default:
                // 默认回退到 apps 请求次数
                java.util.List<MonthlyAppStats> list = monthlyAppStatsRepository.findTopByMonth(monthDate, "requests", pageable);
                for (MonthlyAppStats m : list) {
                    result.add(new org.congcong.controlmanager.dto.TopItem(m.getTargetHost(), nullSafe(m.getRequestsCount())));
                }
        }
        return result;
    }

    /**
     * 基于日度聚合表的 TopN 查询
     */
    public List<org.congcong.controlmanager.dto.TopItem> aggregateDailyTop(String day, String dimension, String metric, int limit) {
        LocalDate dayDate = parseDay(day);
        int topN = limit <= 0 ? 10 : Math.min(limit, 100);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, topN);

        String dim = dimension == null ? "apps" : dimension;
        String met = metric == null ? "requests" : metric;
        java.util.List<org.congcong.controlmanager.dto.TopItem> result = new java.util.ArrayList<>();

        switch (dim) {
            case "users": {
                java.util.List<DailyUserStats> list = dailyUserStatsRepository.findTopByDay(dayDate, met.equals("bytes") ? "bytes" : "requests", pageable);
                for (DailyUserStats d : list) {
                    long val = met.equals("bytes") ? (nullSafe(d.getBytesIn()) + nullSafe(d.getBytesOut())) : nullSafe(d.getRequestsCount());
                    String key = (d.getUsername() != null && !d.getUsername().isBlank()) ? d.getUsername() : String.valueOf(d.getUserId());
                    result.add(new org.congcong.controlmanager.dto.TopItem(key, val));
                }
                break;
            }
            case "apps": {
                java.util.List<DailyAppStats> list = dailyAppStatsRepository.findTopByDay(dayDate, met.equals("bytes") ? "bytes" : "requests", pageable);
                for (DailyAppStats d : list) {
                    long val = met.equals("bytes") ? (nullSafe(d.getBytesIn()) + nullSafe(d.getBytesOut())) : nullSafe(d.getRequestsCount());
                    String key = d.getTargetHost();
                    result.add(new org.congcong.controlmanager.dto.TopItem(key, val));
                }
                break;
            }
            case "user_apps": {
                java.util.List<DailyUserAppStats> list = dailyUserAppStatsRepository.findTopByDay(dayDate, met.equals("bytes") ? "bytes" : "requests", pageable);
                for (DailyUserAppStats d : list) {
                    long val = met.equals("bytes") ? (nullSafe(d.getBytesIn()) + nullSafe(d.getBytesOut())) : nullSafe(d.getRequestsCount());
                    String uname = (d.getUsername() != null && !d.getUsername().isBlank()) ? d.getUsername() : String.valueOf(d.getUserId());
                    String key = uname + "@" + d.getTargetHost();
                    result.add(new org.congcong.controlmanager.dto.TopItem(key, val));
                }
                break;
            }
            case "src_geo": {
                java.util.List<DailySrcGeoStats> list = dailySrcGeoStatsRepository.findTopByDay(dayDate, pageable);
                for (DailySrcGeoStats d : list) {
                    String country = d.getSrcGeoCountry();
                    String city = d.getSrcGeoCity();
                    String key = (city == null || city.isBlank()) ? (country == null ? "unknown" : country) : (country == null ? city : country + "/" + city);
                    result.add(new org.congcong.controlmanager.dto.TopItem(key, nullSafe(d.getRequestsCount())));
                }
                break;
            }
            case "dst_geo": {
                java.util.List<DailyDstGeoStats> list = dailyDstGeoStatsRepository.findTopByDay(dayDate, pageable);
                for (DailyDstGeoStats d : list) {
                    String country = d.getDstGeoCountry();
                    String city = d.getDstGeoCity();
                    String key = (city == null || city.isBlank()) ? (country == null ? "unknown" : country) : (country == null ? city : country + "/" + city);
                    result.add(new org.congcong.controlmanager.dto.TopItem(key, nullSafe(d.getRequestsCount())));
                }
                break;
            }
            default:
                // 默认回退到 apps 请求次数
                java.util.List<DailyAppStats> list = dailyAppStatsRepository.findTopByDay(dayDate, "requests", pageable);
                for (DailyAppStats d : list) {
                    result.add(new org.congcong.controlmanager.dto.TopItem(d.getTargetHost(), nullSafe(d.getRequestsCount())));
                }
        }
        return result;
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

    /**
     * 基于日度聚合表的 TopN 查询（支持时间区间）
     */
    public List<org.congcong.controlmanager.dto.TopItem> aggregateDailyTopRange(String from, String to, String dimension, String metric, int limit, Long userId) {
        LocalDateTime fromTs = parseDate(from);
        LocalDateTime toTs = parseDate(to);
        LocalDate fromDay = fromTs == null ? null : fromTs.toLocalDate();
        LocalDate toDay = toTs == null ? null : toTs.toLocalDate();

        // 默认聚合当前整月，以覆盖原有 month 能力
        if (fromDay == null && toDay == null) {
            LocalDate today = LocalDate.now();
            LocalDate firstOfMonth = LocalDate.of(today.getYear(), today.getMonth(), 1);
            LocalDate lastOfMonth = firstOfMonth.plusMonths(1).minusDays(1);
            fromDay = firstOfMonth;
            toDay = lastOfMonth;
        }

        int topN = limit <= 0 ? 10 : Math.min(limit, 100);

        String dim = dimension == null ? "apps" : dimension;
        String met = metric == null ? "requests" : metric;

        java.util.List<org.congcong.controlmanager.dto.TopItem> result = new java.util.ArrayList<>();
        java.util.Comparator<org.congcong.controlmanager.dto.TopItem> cmp = java.util.Comparator.comparingLong(org.congcong.controlmanager.dto.TopItem::getValue).reversed();

        switch (dim) {
            case "users": {
                java.util.List<DailyUserStats> list = dailyUserStatsRepository.findByDayDateBetween(fromDay, toDay);
                java.util.Map<String, long[]> agg = new java.util.HashMap<>(); // key: userId|username -> [req, bin, bout]
                for (DailyUserStats d : list) {
                    String uname = (d.getUsername() != null && !d.getUsername().isBlank()) ? d.getUsername() : String.valueOf(d.getUserId());
                    String key = d.getUserId() + "|" + (uname == null ? "" : uname);
                    long[] v = agg.computeIfAbsent(key, k -> new long[]{0, 0, 0});
                    v[0] += nullSafe(d.getRequestsCount());
                    v[1] += nullSafe(d.getBytesIn());
                    v[2] += nullSafe(d.getBytesOut());
                }
                java.util.List<org.congcong.controlmanager.dto.TopItem> items = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, long[]> en : agg.entrySet()) {
                    String[] parts = en.getKey().split("\\|");
                    String uname = parts.length > 1 ? parts[1] : parts[0];
                    long[] v = en.getValue();
                    long val = met.equals("bytes") ? (v[1] + v[2]) : v[0];
                    items.add(new org.congcong.controlmanager.dto.TopItem(uname, val));
                }
                items.sort(cmp);
                result = items.size() > topN ? items.subList(0, topN) : items;
                break;
            }
            case "apps": {
                java.util.List<DailyAppStats> list = dailyAppStatsRepository.findByDayDateBetween(fromDay, toDay);
                java.util.Map<String, long[]> agg = new java.util.HashMap<>(); // key: host -> [req, bin, bout]
                for (DailyAppStats d : list) {
                    String key = d.getTargetHost();
                    long[] v = agg.computeIfAbsent(key, k -> new long[]{0, 0, 0});
                    v[0] += nullSafe(d.getRequestsCount());
                    v[1] += nullSafe(d.getBytesIn());
                    v[2] += nullSafe(d.getBytesOut());
                }
                java.util.List<org.congcong.controlmanager.dto.TopItem> items = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, long[]> en : agg.entrySet()) {
                    String key = en.getKey();
                    long[] v = en.getValue();
                    long val = met.equals("bytes") ? (v[1] + v[2]) : v[0];
                    items.add(new org.congcong.controlmanager.dto.TopItem(key, val));
                }
                items.sort(cmp);
                result = items.size() > topN ? items.subList(0, topN) : items;
                break;
            }
            case "user_apps": {
                // 如果userId有值，则根据userId过滤，通过DailyUserAppStats聚合得到应用统计数据
                java.util.List<DailyUserAppStats> list;
                if (userId != null) {
                    list = dailyUserAppStatsRepository.findByUserIdAndDayDateBetween(userId, fromDay, toDay);
                } else {
                    list = dailyUserAppStatsRepository.findByDayDateBetween(fromDay, toDay);
                }
                java.util.Map<String, long[]> agg = new java.util.HashMap<>(); // key: userId|username|host -> [req, bin, bout]
                for (DailyUserAppStats d : list) {
                    String uname = (d.getUsername() != null && !d.getUsername().isBlank()) ? d.getUsername() : String.valueOf(d.getUserId());
                    String key = d.getUserId() + "|" + (uname == null ? "" : uname) + "|" + d.getTargetHost();
                    long[] v = agg.computeIfAbsent(key, k -> new long[]{0, 0, 0});
                    v[0] += nullSafe(d.getRequestsCount());
                    v[1] += nullSafe(d.getBytesIn());
                    v[2] += nullSafe(d.getBytesOut());
                }
                java.util.List<org.congcong.controlmanager.dto.TopItem> items = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, long[]> en : agg.entrySet()) {
                    String[] parts = en.getKey().split("\\|");
                    String uname = parts.length > 2 ? parts[1] : parts[0];
                    String host = parts.length > 2 ? parts[2] : "";
                    long[] v = en.getValue();
                    long val = met.equals("bytes") ? (v[1] + v[2]) : v[0];
                    items.add(new org.congcong.controlmanager.dto.TopItem(uname + "@" + host, val));
                }
                items.sort(cmp);
                result = items.size() > topN ? items.subList(0, topN) : items;
                break;
            }
            case "src_geo": {
                java.util.List<DailySrcGeoStats> list = dailySrcGeoStatsRepository.findByDayDateBetween(fromDay, toDay);
                java.util.Map<String, long[]> agg = new java.util.HashMap<>(); // key: country|city -> [req]
                for (DailySrcGeoStats d : list) {
                    String country = d.getSrcGeoCountry();
                    String city = d.getSrcGeoCity();
                    String key = (country == null ? "" : country) + "|" + (city == null ? "" : city);
                    long[] v = agg.computeIfAbsent(key, k -> new long[]{0});
                    v[0] += nullSafe(d.getRequestsCount());
                }
                java.util.List<org.congcong.controlmanager.dto.TopItem> items = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, long[]> en : agg.entrySet()) {
                    String[] parts = en.getKey().split("\\|");
                    String key = (parts[0] == null ? "" : parts[0]) + (parts.length > 1 && parts[1] != null && !parts[1].isEmpty() ? "/" + parts[1] : "");
                    long[] v = en.getValue();
                    items.add(new org.congcong.controlmanager.dto.TopItem(key.isEmpty() ? "未知" : key, v[0]));
                }
                items.sort(cmp);
                result = items.size() > topN ? items.subList(0, topN) : items;
                break;
            }
            case "dst_geo": {
                java.util.List<DailyDstGeoStats> list = dailyDstGeoStatsRepository.findByDayDateBetween(fromDay, toDay);
                java.util.Map<String, long[]> agg = new java.util.HashMap<>(); // key: country|city -> [req]
                for (DailyDstGeoStats d : list) {
                    String country = d.getDstGeoCountry();
                    String city = d.getDstGeoCity();
                    String key = (country == null ? "" : country) + "|" + (city == null ? "" : city);
                    long[] v = agg.computeIfAbsent(key, k -> new long[]{0});
                    v[0] += nullSafe(d.getRequestsCount());
                }
                java.util.List<org.congcong.controlmanager.dto.TopItem> items = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, long[]> en : agg.entrySet()) {
                    String[] parts = en.getKey().split("\\|");
                    String key = (parts[0] == null ? "" : parts[0]) + (parts.length > 1 && parts[1] != null && !parts[1].isEmpty() ? "/" + parts[1] : "");
                    long[] v = en.getValue();
                    items.add(new org.congcong.controlmanager.dto.TopItem(key.isEmpty() ? "未知" : key, v[0]));
                }
                items.sort(cmp);
                result = items.size() > topN ? items.subList(0, topN) : items;
                break;
            }
            default:
                break;
        }
        return result;
    }

    private String topKey(AccessLogEntity e, String dimension) {
        switch (dimension == null ? "users" : dimension) {
            case "users":
                return e.getUsername();
            case "apps": // apps(host)
            case "apps(host)":
                String host = e.getOriginalTargetHost();
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

    private void updateMonthlyAggregates(List<AccessLog> logs) {
        if (logs == null || logs.isEmpty()) return;

        // 批量聚合，减少数据库查找次数
        Map<String, long[]> userAgg = new HashMap<>();          // key: month|userId|username -> [requests, bytesIn, bytesOut]
        Map<String, long[]> appAgg = new HashMap<>();           // key: month|host -> [requests, bytesIn, bytesOut]
        Map<String, long[]> userAppAgg = new HashMap<>();       // key: month|userId|username|host -> [requests, bytesIn, bytesOut]
        Map<String, long[]> srcGeoAgg = new HashMap<>();        // key: month|country|city -> [requests]
        Map<String, long[]> dstGeoAgg = new HashMap<>();        // key: month|country|city -> [requests]

        for (AccessLog l : logs) {
            LocalDateTime ts = toLocalDateTime(l.getTs());
            LocalDate monthDate = LocalDate.of(ts.getYear(), ts.getMonth(), 1);
            long req = 1L;
            long bin = l.getBytesIn() == null ? 0L : l.getBytesIn();
            long bout = l.getBytesOut() == null ? 0L : l.getBytesOut();

            Long userId = l.getUserId();
            String username = l.getUsername();
            String host = parseMainDomain(l.getOriginalTargetHost());

            // 用户流量
            if (userId != null) {
                String key = monthDate + "|" + userId + "|" + (username == null ? "" : username);
                long[] v = userAgg.computeIfAbsent(key, k -> new long[]{0, 0, 0});
                v[0] += req; v[1] += bin; v[2] += bout;
            }

            // 应用统计
            if (host != null && !host.isBlank()) {
                String key = monthDate + "|" + host;
                long[] v = appAgg.computeIfAbsent(key, k -> new long[]{0, 0, 0});
                v[0] += req; v[1] += bin; v[2] += bout;
            }

            // 用户访问应用统计
            if (userId != null && host != null && !host.isBlank()) {
                String key = monthDate + "|" + userId + "|" + (username == null ? "" : username) + "|" + host;
                long[] v = userAppAgg.computeIfAbsent(key, k -> new long[]{0, 0, 0});
                v[0] += req; v[1] += bin; v[2] += bout;
            }

            // 地理来源统计
            String srcCountry = l.getSrcGeoCountry();
            String srcCity = l.getSrcGeoCity();
            if (notBlank(srcCountry) || notBlank(srcCity)) {
                String key = monthDate + "|" + nullToEmpty(srcCountry) + "|" + nullToEmpty(srcCity);
                long[] v = srcGeoAgg.computeIfAbsent(key, k -> new long[]{0});
                v[0] += req;
            }

            // 地理目标统计
            String dstCountry = l.getDstGeoCountry();
            String dstCity = l.getDstGeoCity();
            if (notBlank(dstCountry) || notBlank(dstCity)) {
                String key = monthDate + "|" + nullToEmpty(dstCountry) + "|" + nullToEmpty(dstCity);
                long[] v = dstGeoAgg.computeIfAbsent(key, k -> new long[]{0});
                v[0] += req;
            }
        }

        LocalDateTime now = LocalDateTime.now();

        // 写入用户聚合
        for (Map.Entry<String, long[]> en : userAgg.entrySet()) {
            String[] parts = en.getKey().split("\\|", -1);
            LocalDate monthDate = LocalDate.parse(parts[0]);
            Long userId = Long.parseLong(parts[1]);
            String username = parts[2];
            long[] v = en.getValue();
            MonthlyUserStats m = monthlyUserStatsRepository.findByMonthDateAndUserId(monthDate, userId).orElseGet(() -> {
                MonthlyUserStats x = new MonthlyUserStats();
                x.setMonthDate(monthDate);
                x.setUserId(userId);
                x.setUsername(username);
                x.setRequestsCount(0L);
                x.setBytesIn(0L);
                x.setBytesOut(0L);
                return x;
            });
            if (username != null && !username.isBlank()) m.setUsername(username);
            m.setRequestsCount(nullSafe(m.getRequestsCount()) + v[0]);
            m.setBytesIn(nullSafe(m.getBytesIn()) + v[1]);
            m.setBytesOut(nullSafe(m.getBytesOut()) + v[2]);
            m.setUpdatedAt(now);
            monthlyUserStatsRepository.save(m);
        }

        // 写入应用聚合
        for (Map.Entry<String, long[]> en : appAgg.entrySet()) {
            String[] parts = en.getKey().split("\\|", -1);
            LocalDate monthDate = LocalDate.parse(parts[0]);
            String host = parts[1];
            long[] v = en.getValue();
            MonthlyAppStats m = monthlyAppStatsRepository.findByMonthDateAndTargetHost(monthDate, host).orElseGet(() -> {
                MonthlyAppStats x = new MonthlyAppStats();
                x.setMonthDate(monthDate);
                x.setTargetHost(host);
                x.setRequestsCount(0L);
                x.setBytesIn(0L);
                x.setBytesOut(0L);
                return x;
            });
            m.setRequestsCount(nullSafe(m.getRequestsCount()) + v[0]);
            m.setBytesIn(nullSafe(m.getBytesIn()) + v[1]);
            m.setBytesOut(nullSafe(m.getBytesOut()) + v[2]);
            m.setUpdatedAt(now);
            monthlyAppStatsRepository.save(m);
        }

        // 写入用户-应用聚合
        for (Map.Entry<String, long[]> en : userAppAgg.entrySet()) {
            String[] parts = en.getKey().split("\\|", -1);
            LocalDate monthDate = LocalDate.parse(parts[0]);
            Long userId = Long.parseLong(parts[1]);
            String username = parts[2];
            String host = parts[3];
            long[] v = en.getValue();
            MonthlyUserAppStats m = monthlyUserAppStatsRepository.findByMonthDateAndUserIdAndTargetHost(monthDate, userId, host).orElseGet(() -> {
                MonthlyUserAppStats x = new MonthlyUserAppStats();
                x.setMonthDate(monthDate);
                x.setUserId(userId);
                x.setUsername(username);
                x.setTargetHost(host);
                x.setRequestsCount(0L);
                x.setBytesIn(0L);
                x.setBytesOut(0L);
                return x;
            });
            if (username != null && !username.isBlank()) m.setUsername(username);
            m.setRequestsCount(nullSafe(m.getRequestsCount()) + v[0]);
            m.setBytesIn(nullSafe(m.getBytesIn()) + v[1]);
            m.setBytesOut(nullSafe(m.getBytesOut()) + v[2]);
            m.setUpdatedAt(now);
            monthlyUserAppStatsRepository.save(m);
        }

        // 写入源地理聚合
        for (Map.Entry<String, long[]> en : srcGeoAgg.entrySet()) {
            String[] parts = en.getKey().split("\\|", -1);
            LocalDate monthDate = LocalDate.parse(parts[0]);
            String country = emptyToNull(parts[1]);
            String city = emptyToNull(parts[2]);
            long[] v = en.getValue();
            MonthlySrcGeoStats m = monthlySrcGeoStatsRepository.findByMonthDateAndSrcGeoCountryAndSrcGeoCity(monthDate, country, city).orElseGet(() -> {
                MonthlySrcGeoStats x = new MonthlySrcGeoStats();
                x.setMonthDate(monthDate);
                x.setSrcGeoCountry(country);
                x.setSrcGeoCity(city);
                x.setRequestsCount(0L);
                return x;
            });
            m.setRequestsCount(nullSafe(m.getRequestsCount()) + v[0]);
            m.setUpdatedAt(now);
            monthlySrcGeoStatsRepository.save(m);
        }

        // 写入目标地理聚合
        for (Map.Entry<String, long[]> en : dstGeoAgg.entrySet()) {
            String[] parts = en.getKey().split("\\|", -1);
            LocalDate monthDate = LocalDate.parse(parts[0]);
            String country = emptyToNull(parts[1]);
            String city = emptyToNull(parts[2]);
            long[] v = en.getValue();
            MonthlyDstGeoStats m = monthlyDstGeoStatsRepository.findByMonthDateAndDstGeoCountryAndDstGeoCity(monthDate, country, city).orElseGet(() -> {
                MonthlyDstGeoStats x = new MonthlyDstGeoStats();
                x.setMonthDate(monthDate);
                x.setDstGeoCountry(country);
                x.setDstGeoCity(city);
                x.setRequestsCount(0L);
                return x;
            });
            m.setRequestsCount(nullSafe(m.getRequestsCount()) + v[0]);
            m.setUpdatedAt(now);
            monthlyDstGeoStatsRepository.save(m);
        }
    }

    private void updateDailyAggregates(List<AccessLog> logs) {
        if (logs == null || logs.isEmpty()) return;

        // 批量聚合，减少数据库查找次数
        Map<String, long[]> userAgg = new HashMap<>();          // key: day|userId|username -> [requests, bytesIn, bytesOut]
        Map<String, long[]> appAgg = new HashMap<>();           // key: day|host -> [requests, bytesIn, bytesOut]
        Map<String, long[]> userAppAgg = new HashMap<>();       // key: day|userId|username|host -> [requests, bytesIn, bytesOut]
        Map<String, long[]> srcGeoAgg = new HashMap<>();        // key: day|country|city -> [requests]
        Map<String, long[]> dstGeoAgg = new HashMap<>();        // key: day|country|city -> [requests]

        for (AccessLog l : logs) {
            LocalDateTime ts = toLocalDateTime(l.getTs());
            LocalDate dayDate = ts.toLocalDate();
            long req = 1L;
            long bin = l.getBytesIn() == null ? 0L : l.getBytesIn();
            long bout = l.getBytesOut() == null ? 0L : l.getBytesOut();

            Long userId = l.getUserId();
            String username = l.getUsername();
            String host = parseMainDomain(l.getOriginalTargetHost());

            // 用户流量
            if (userId != null) {
                String key = dayDate + "|" + userId + "|" + (username == null ? "" : username);
                long[] v = userAgg.computeIfAbsent(key, k -> new long[]{0, 0, 0});
                v[0] += req; v[1] += bin; v[2] += bout;
            }

            // 应用统计
            if (host != null && !host.isBlank()) {
                String key = dayDate + "|" + host;
                long[] v = appAgg.computeIfAbsent(key, k -> new long[]{0, 0, 0});
                v[0] += req; v[1] += bin; v[2] += bout;
            }

            // 用户访问应用统计
            if (userId != null && host != null && !host.isBlank()) {
                String key = dayDate + "|" + userId + "|" + (username == null ? "" : username) + "|" + host;
                long[] v = userAppAgg.computeIfAbsent(key, k -> new long[]{0, 0, 0});
                v[0] += req; v[1] += bin; v[2] += bout;
            }

            // 地理来源统计
            String srcCountry = l.getSrcGeoCountry();
            String srcCity = l.getSrcGeoCity();
            if (notBlank(srcCountry) || notBlank(srcCity)) {
                String key = dayDate + "|" + nullToEmpty(srcCountry) + "|" + nullToEmpty(srcCity);
                long[] v = srcGeoAgg.computeIfAbsent(key, k -> new long[]{0});
                v[0] += req;
            }

            // 地理目标统计
            String dstCountry = l.getDstGeoCountry();
            String dstCity = l.getDstGeoCity();
            if (notBlank(dstCountry) || notBlank(dstCity)) {
                String key = dayDate + "|" + nullToEmpty(dstCountry) + "|" + nullToEmpty(dstCity);
                long[] v = dstGeoAgg.computeIfAbsent(key, k -> new long[]{0});
                v[0] += req;
            }
        }

        LocalDateTime now = LocalDateTime.now();

        // 写入用户聚合
        for (Map.Entry<String, long[]> en : userAgg.entrySet()) {
            String[] parts = en.getKey().split("\\|", -1);
            LocalDate dayDate = LocalDate.parse(parts[0]);
            Long userId = Long.parseLong(parts[1]);
            String username = parts[2];
            long[] v = en.getValue();
            DailyUserStats d = dailyUserStatsRepository.findByDayDateAndUserId(dayDate, userId).orElseGet(() -> {
                DailyUserStats x = new DailyUserStats();
                x.setDayDate(dayDate);
                x.setUserId(userId);
                x.setUsername(username);
                x.setRequestsCount(0L);
                x.setBytesIn(0L);
                x.setBytesOut(0L);
                return x;
            });
            if (username != null && !username.isBlank()) d.setUsername(username);
            d.setRequestsCount(nullSafe(d.getRequestsCount()) + v[0]);
            d.setBytesIn(nullSafe(d.getBytesIn()) + v[1]);
            d.setBytesOut(nullSafe(d.getBytesOut()) + v[2]);
            d.setUpdatedAt(now);
            dailyUserStatsRepository.save(d);
        }

        // 写入应用聚合
        for (Map.Entry<String, long[]> en : appAgg.entrySet()) {
            String[] parts = en.getKey().split("\\|", -1);
            LocalDate dayDate = LocalDate.parse(parts[0]);
            String host = parts[1];
            long[] v = en.getValue();
            DailyAppStats d = dailyAppStatsRepository.findByDayDateAndTargetHost(dayDate, host).orElseGet(() -> {
                DailyAppStats x = new DailyAppStats();
                x.setDayDate(dayDate);
                x.setTargetHost(host);
                x.setRequestsCount(0L);
                x.setBytesIn(0L);
                x.setBytesOut(0L);
                return x;
            });
            d.setRequestsCount(nullSafe(d.getRequestsCount()) + v[0]);
            d.setBytesIn(nullSafe(d.getBytesIn()) + v[1]);
            d.setBytesOut(nullSafe(d.getBytesOut()) + v[2]);
            d.setUpdatedAt(now);
            dailyAppStatsRepository.save(d);
        }

        // 写入用户-应用聚合
        for (Map.Entry<String, long[]> en : userAppAgg.entrySet()) {
            String[] parts = en.getKey().split("\\|", -1);
            LocalDate dayDate = LocalDate.parse(parts[0]);
            Long userId = Long.parseLong(parts[1]);
            String username = parts[2];
            String host = parts[3];
            long[] v = en.getValue();
            DailyUserAppStats d = dailyUserAppStatsRepository.findByDayDateAndUserIdAndTargetHost(dayDate, userId, host).orElseGet(() -> {
                DailyUserAppStats x = new DailyUserAppStats();
                x.setDayDate(dayDate);
                x.setUserId(userId);
                x.setUsername(username);
                x.setTargetHost(host);
                x.setRequestsCount(0L);
                x.setBytesIn(0L);
                x.setBytesOut(0L);
                return x;
            });
            if (username != null && !username.isBlank()) d.setUsername(username);
            d.setRequestsCount(nullSafe(d.getRequestsCount()) + v[0]);
            d.setBytesIn(nullSafe(d.getBytesIn()) + v[1]);
            d.setBytesOut(nullSafe(d.getBytesOut()) + v[2]);
            d.setUpdatedAt(now);
            dailyUserAppStatsRepository.save(d);
        }

        // 写入源地理聚合
        for (Map.Entry<String, long[]> en : srcGeoAgg.entrySet()) {
            String[] parts = en.getKey().split("\\|", -1);
            LocalDate dayDate = LocalDate.parse(parts[0]);
            String country = emptyToNull(parts[1]);
            String city = emptyToNull(parts[2]);
            long[] v = en.getValue();
            DailySrcGeoStats d = dailySrcGeoStatsRepository.findByDayDateAndSrcGeoCountryAndSrcGeoCity(dayDate, country, city).orElseGet(() -> {
                DailySrcGeoStats x = new DailySrcGeoStats();
                x.setDayDate(dayDate);
                x.setSrcGeoCountry(country);
                x.setSrcGeoCity(city);
                x.setRequestsCount(0L);
                return x;
            });
            d.setRequestsCount(nullSafe(d.getRequestsCount()) + v[0]);
            d.setUpdatedAt(now);
            dailySrcGeoStatsRepository.save(d);
        }

        // 写入目标地理聚合
        for (Map.Entry<String, long[]> en : dstGeoAgg.entrySet()) {
            String[] parts = en.getKey().split("\\|", -1);
            LocalDate dayDate = LocalDate.parse(parts[0]);
            String country = emptyToNull(parts[1]);
            String city = emptyToNull(parts[2]);
            long[] v = en.getValue();
            DailyDstGeoStats d = dailyDstGeoStatsRepository.findByDayDateAndDstGeoCountryAndDstGeoCity(dayDate, country, city).orElseGet(() -> {
                DailyDstGeoStats x = new DailyDstGeoStats();
                x.setDayDate(dayDate);
                x.setDstGeoCountry(country);
                x.setDstGeoCity(city);
                x.setRequestsCount(0L);
                return x;
            });
            d.setRequestsCount(nullSafe(d.getRequestsCount()) + v[0]);
            d.setUpdatedAt(now);
            dailyDstGeoStatsRepository.save(d);
        }
    }

    private LocalDate parseMonth(String month) {
        if (month == null || month.isBlank()) {
            LocalDateTime now = LocalDateTime.now();
            return LocalDate.of(now.getYear(), now.getMonth(), 1);
        }
        try {
            if (month.matches("^\\d{4}-\\d{2}$")) {
                int y = Integer.parseInt(month.substring(0, 4));
                int m = Integer.parseInt(month.substring(5, 7));
                return LocalDate.of(y, m, 1);
            }
            // 支持毫秒时间戳
            if (month.matches("^\\d{13}$")) {
                long ms = Long.parseLong(month);
                LocalDateTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault());
                return LocalDate.of(t.getYear(), t.getMonth(), 1);
            }
        } catch (Exception ignored) {}
        // 兜底当前月
        LocalDateTime now = LocalDateTime.now();
        return LocalDate.of(now.getYear(), now.getMonth(), 1);
    }

    private LocalDate parseDay(String day) {
        if (day == null || day.isBlank()) {
            LocalDateTime now = LocalDateTime.now();
            return LocalDate.of(now.getYear(), now.getMonth(), now.getDayOfMonth());
        }
        try {
            if (day.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                int y = Integer.parseInt(day.substring(0, 4));
                int m = Integer.parseInt(day.substring(5, 7));
                int d = Integer.parseInt(day.substring(8, 10));
                return LocalDate.of(y, m, d);
            }
            // 支持毫秒时间戳
            if (day.matches("^\\d{13}$")) {
                long ms = Long.parseLong(day);
                LocalDateTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault());
                return LocalDate.of(t.getYear(), t.getMonth(), t.getDayOfMonth());
            }
        } catch (Exception ignored) {}
        // 兜底当前日
        LocalDateTime now = LocalDateTime.now();
        return LocalDate.of(now.getYear(), now.getMonth(), now.getDayOfMonth());
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private String nullToEmpty(String s) { return s == null ? "" : s; }
    private String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
    private long nullSafe(Long v) { return v == null ? 0L : v; }

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
                e.getOriginalTargetHost(), e.getRewriteTargetHost(), e.getSrcGeoCountry(), e.getDstGeoCountry()
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

    /**
     * 解析主机名，如果是域名则提取主域名（去掉子域名），如果是IP地址则保持不变
     * @param host 原始主机名或IP地址
     * @return 处理后的主机名
     */
    private String parseMainDomain(String host) {
        if (host == null || host.isBlank()) {
            return host;
        }
        
        // 检查是否为IP地址（IPv4或IPv6）
        if (isIpAddress(host)) {
            return host; // IP地址保持不变
        }
        
        // 处理域名，提取主域名
        String[] parts = host.split("\\.");
        if (parts.length <= 2) {
            return host; // 已经是主域名或单级域名
        }
        
        // 提取最后两个部分作为主域名（例如：www.example.com -> example.com）
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
    
    /**
     * 检查字符串是否为IP地址（IPv4或IPv6）
     * @param host 待检查的字符串
     * @return 如果是IP地址返回true，否则返回false
     */
    private boolean isIpAddress(String host) {
        // 检查IPv4地址
        if (host.matches("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")) {
            return true;
        }
        
        // 检查IPv6地址（简化版本，支持常见格式）
        if (host.contains(":") && host.matches("^([0-9a-fA-F]{0,4}:){1,7}[0-9a-fA-F]{0,4}$|^::1$|^::$")) {
            return true;
        }
        
        return false;
    }

    private LocalDateTime parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.matches("^\\d{13}$")) { // 毫秒时间戳
                long ms = Long.parseLong(s);
                return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault());
            }
            if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) { // 仅日期
                int y = Integer.parseInt(s.substring(0, 4));
                int m = Integer.parseInt(s.substring(5, 7));
                int d = Integer.parseInt(s.substring(8, 10));
                return LocalDateTime.of(y, m, d, 0, 0, 0);
            }
            try {
                // 支持本地日期时间（无时区）
                return LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException ignored) {}
            // 支持带时区的时间戳（UTC 或偏移）
            java.time.Instant instant = java.time.Instant.parse(s);
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        } catch (DateTimeParseException | NumberFormatException ex) {
            return null;
        }
    }
}