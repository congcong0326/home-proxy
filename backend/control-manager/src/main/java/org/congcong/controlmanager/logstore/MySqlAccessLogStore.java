//package org.congcong.controlmanager.logstore;
//
//import lombok.RequiredArgsConstructor;
//import org.congcong.common.dto.AccessLog;
//import org.congcong.controlmanager.dto.*;
//import org.congcong.controlmanager.entity.AccessLogEntity;
//import org.congcong.controlmanager.entity.agg.*;
//import org.congcong.controlmanager.repository.AccessLogRepository;
//import org.congcong.controlmanager.repository.agg.*;
//import org.congcong.controlmanager.dto.TimeSeriesPoint;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.PageRequest;
//import org.springframework.data.domain.Pageable;
//import org.springframework.data.domain.Sort;
//import org.springframework.data.jpa.domain.Specification;
//import org.springframework.stereotype.Component;
//
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.time.ZoneId;
//import java.time.format.DateTimeParseException;
//import java.util.*;
//import java.util.stream.Collectors;
//
//@Component
//@RequiredArgsConstructor
//public class MySqlAccessLogStore implements AccessLogStore {
//    private final AccessLogRepository accessLogRepository;
//    private final DailyUserStatsRepository dailyUserStatsRepository;
//    private final DailyAppStatsRepository dailyAppStatsRepository;
//    private final DailyUserAppStatsRepository dailyUserAppStatsRepository;
//    private final DailySrcGeoStatsRepository dailySrcGeoStatsRepository;
//    private final DailyDstGeoStatsRepository dailyDstGeoStatsRepository;
//    private final MinuteTrafficStatsRepository minuteTrafficStatsRepository;
//
//    @Override
//    public int ingest(List<AccessLog> logs) {
//        if (logs == null || logs.isEmpty()) return 0;
//        List<AccessLogEntity> entities = new ArrayList<>(logs.size());
//        for (AccessLog l : logs) {
//            AccessLogEntity e = new AccessLogEntity();
//            e.setTs(toLocalDateTime(l.getTs()));
//            e.setRequestId(l.getRequestId());
//            e.setUserId(l.getUserId());
//            e.setUsername(l.getUsername());
//            e.setProxyName(l.getProxyName());
//            e.setInboundId(l.getInboundId());
//            e.setClientIp(l.getClientIp());
//            e.setClientPort(l.getClientPort());
//            e.setSrcGeoCountry(l.getSrcGeoCountry());
//            e.setSrcGeoCity(l.getSrcGeoCity());
//            e.setOriginalTargetHost(l.getOriginalTargetHost());
//            e.setOriginalTargetIP(l.getOriginalTargetIP());
//            e.setOriginalTargetPort(l.getOriginalTargetPort());
//            e.setRewriteTargetHost(l.getRewriteTargetHost());
//            e.setRewriteTargetPort(l.getRewriteTargetPort());
//            e.setDstGeoCountry(l.getDstGeoCountry());
//            e.setDstGeoCity(l.getDstGeoCity());
//            e.setInboundProtocolType(l.getInboundProtocolType());
//            e.setOutboundProtocolType(l.getOutboundProtocolType());
//            e.setRoutePolicyName(l.getRoutePolicyName());
//            e.setRoutePolicyId(l.getRoutePolicyId());
//            e.setBytesIn(l.getBytesIn());
//            e.setBytesOut(l.getBytesOut());
//            e.setStatus(l.getStatus());
//            e.setErrorCode(l.getErrorCode());
//            e.setErrorMsg(l.getErrorMsg());
//            e.setRequestDurationMs(l.getRequestDurationMs());
//            e.setDnsDurationMs(l.getDnsDurationMs());
//            e.setConnectDurationMs(l.getConnectDurationMs());
//            e.setConnectTargetDurationMs(l.getConnectTargetDurationMs());
//            entities.add(e);
//        }
//        accessLogRepository.saveAll(entities);
//        return entities.size();
//    }
//
//    @Override
//    public PageResponse<AccessLogListItem> queryAccessLogs(AccessLogQueryRequest req) {
//        Specification<AccessLogEntity> spec = buildAccessLogSpec(req);
//        Sort sort = parseSort(req.getSort());
//        int page = req.getPage() == null ? 0 : Math.max(req.getPage(), 0);
//        int size = req.getSize() == null ? 20 : Math.min(Math.max(req.getSize(), 1), 100);
//        Pageable pageable = PageRequest.of(page, size, sort);
//        Page<AccessLogEntity> result = accessLogRepository.findAll(spec, pageable);
//        List<AccessLogListItem> items = result.getContent().stream().map(this::toListItem).collect(Collectors.toList());
//        return new PageResponse<>(items, page + 1, size, result.getTotalElements());
//    }
//
//    @Override
//    public Optional<AccessLogDetail> getAccessLogDetail(Long id) {
//        return accessLogRepository.findById(id).map(this::toDetail);
//    }
//
//    @Override
//    public Optional<AccessLogDetail> getAccessLogDetail(String id) {
//        return Optional.empty();
//    }
//
//    @Override
//    public List<TopItem> aggregateDailyTopRange(String from, String to, String dimension, String metric, int limit, Long userId) {
//        LocalDateTime fromTs = parseDate(from);
//        LocalDateTime toTs = parseDate(to);
//        LocalDate fromDay = fromTs == null ? null : fromTs.toLocalDate();
//        LocalDate toDay = toTs == null ? null : toTs.toLocalDate();
//        if (fromDay == null && toDay == null) {
//            LocalDate today = LocalDate.now();
//            LocalDate firstOfMonth = LocalDate.of(today.getYear(), today.getMonth(), 1);
//            LocalDate lastOfMonth = firstOfMonth.plusMonths(1).minusDays(1);
//            fromDay = firstOfMonth;
//            toDay = lastOfMonth;
//        }
//        int topN = limit <= 0 ? 10 : Math.min(limit, 100);
//        String dim = dimension == null ? "apps" : dimension;
//        String met = metric == null ? "requests" : metric;
//        List<TopItem> result = new ArrayList<>();
//        Comparator<TopItem> cmp = Comparator.comparingLong(TopItem::getValue).reversed();
//        switch (dim) {
//            case "users": {
//                List<DailyUserStats> list = dailyUserStatsRepository.findByDayDateBetween(fromDay, toDay);
//                Map<String, long[]> agg = new HashMap<>();
//                for (DailyUserStats d : list) {
//                    String uname = (d.getUsername() != null && !d.getUsername().isBlank()) ? d.getUsername() : String.valueOf(d.getUserId());
//                    String key = uname;
//                    long[] v = agg.computeIfAbsent(key, k -> new long[]{0, 0, 0});
//                    v[0] += nullSafe(d.getRequestsCount());
//                    v[1] += nullSafe(d.getBytesIn());
//                    v[2] += nullSafe(d.getBytesOut());
//                }
//                List<TopItem> items = new ArrayList<>();
//                for (Map.Entry<String, long[]> en : agg.entrySet()) {
//                    long[] v = en.getValue();
//                    long val = met.equals("bytes") ? (v[1] + v[2]) : v[0];
//                    items.add(new TopItem(en.getKey(), val));
//                }
//                items.sort(cmp);
//                result = items.size() > topN ? items.subList(0, topN) : items;
//                break;
//            }
//            case "apps": {
//                List<DailyAppStats> list = dailyAppStatsRepository.findByDayDateBetween(fromDay, toDay);
//                Map<String, long[]> agg = new HashMap<>();
//                for (DailyAppStats d : list) {
//                    String key = d.getTargetHost();
//                    long[] v = agg.computeIfAbsent(key, k -> new long[]{0, 0, 0});
//                    v[0] += nullSafe(d.getRequestsCount());
//                    v[1] += nullSafe(d.getBytesIn());
//                    v[2] += nullSafe(d.getBytesOut());
//                }
//                List<TopItem> items = new ArrayList<>();
//                for (Map.Entry<String, long[]> en : agg.entrySet()) {
//                    long[] v = en.getValue();
//                    long val = met.equals("bytes") ? (v[1] + v[2]) : v[0];
//                    items.add(new TopItem(en.getKey(), val));
//                }
//                items.sort(cmp);
//                result = items.size() > topN ? items.subList(0, topN) : items;
//                break;
//            }
//            case "user_apps": {
//                List<DailyUserAppStats> list;
//                if (userId != null) {
//                    list = dailyUserAppStatsRepository.findByUserIdAndDayDateBetween(userId, fromDay, toDay);
//                } else {
//                    list = dailyUserAppStatsRepository.findByDayDateBetween(fromDay, toDay);
//                }
//                Map<String, long[]> agg = new HashMap<>();
//                for (DailyUserAppStats d : list) {
//                    String uname = (d.getUsername() != null && !d.getUsername().isBlank()) ? d.getUsername() : String.valueOf(d.getUserId());
//                    String key = userId != null ? d.getTargetHost() : uname + "@" + d.getTargetHost();
//                    long[] v = agg.computeIfAbsent(key, k -> new long[]{0, 0, 0});
//                    v[0] += nullSafe(d.getRequestsCount());
//                    v[1] += nullSafe(d.getBytesIn());
//                    v[2] += nullSafe(d.getBytesOut());
//                }
//                List<TopItem> items = new ArrayList<>();
//                for (Map.Entry<String, long[]> en : agg.entrySet()) {
//                    long[] v = en.getValue();
//                    long val = met.equals("bytes") ? (v[1] + v[2]) : v[0];
//                    items.add(new TopItem(en.getKey(), val));
//                }
//                items.sort(cmp);
//                result = items.size() > topN ? items.subList(0, topN) : items;
//                break;
//            }
//            case "src_geo": {
//                List<DailySrcGeoStats> list = dailySrcGeoStatsRepository.findByDayDateBetween(fromDay, toDay);
//                Map<String, long[]> agg = new HashMap<>();
//                for (DailySrcGeoStats d : list) {
//                    String key = (d.getSrcGeoCountry() == null ? "" : d.getSrcGeoCountry()) + (d.getSrcGeoCity() == null || d.getSrcGeoCity().isEmpty() ? "" : "/" + d.getSrcGeoCity());
//                    long[] v = agg.computeIfAbsent(key, k -> new long[]{0});
//                    v[0] += nullSafe(d.getRequestsCount());
//                }
//                List<TopItem> items = new ArrayList<>();
//                for (Map.Entry<String, long[]> en : agg.entrySet()) {
//                    long[] v = en.getValue();
//                    items.add(new TopItem(en.getKey().isEmpty() ? "未知" : en.getKey(), v[0]));
//                }
//                items.sort(cmp);
//                result = items.size() > topN ? items.subList(0, topN) : items;
//                break;
//            }
//            case "dst_geo": {
//                List<DailyDstGeoStats> list = dailyDstGeoStatsRepository.findByDayDateBetween(fromDay, toDay);
//                Map<String, long[]> agg = new HashMap<>();
//                for (DailyDstGeoStats d : list) {
//                    String key = (d.getDstGeoCountry() == null ? "" : d.getDstGeoCountry()) + (d.getDstGeoCity() == null || d.getDstGeoCity().isEmpty() ? "" : "/" + d.getDstGeoCity());
//                    long[] v = agg.computeIfAbsent(key, k -> new long[]{0});
//                    v[0] += nullSafe(d.getRequestsCount());
//                }
//                List<TopItem> items = new ArrayList<>();
//                for (Map.Entry<String, long[]> en : agg.entrySet()) {
//                    long[] v = en.getValue();
//                    items.add(new TopItem(en.getKey().isEmpty() ? "未知" : en.getKey(), v[0]));
//                }
//                items.sort(cmp);
//                result = items.size() > topN ? items.subList(0, topN) : items;
//                break;
//            }
//            default:
//                break;
//        }
//        return result;
//    }
//
//    @Override
//    public List<TimeSeriesPoint> getGlobalTrafficTrend(LocalDateTime from, LocalDateTime to) {
//        List<MinuteTrafficStats> userTrafficTrend = minuteTrafficStatsRepository.findGlobalTrafficTrend(from, to);
//        Map<LocalDateTime, TimeSeriesPoint> map = new HashMap<>();
//        for (MinuteTrafficStats m : userTrafficTrend) {
//            TimeSeriesPoint timeSeriesPoint = map.get(m.getMinuteTime());
//            if (timeSeriesPoint == null) {
//                map.put(m.getMinuteTime(), toTimeSeriesPoint(m));
//            } else {
//                timeSeriesPoint.setByteIn(timeSeriesPoint.getByteIn() + m.getByteIn());
//                timeSeriesPoint.setByteOut(timeSeriesPoint.getByteOut() + m.getByteOut());
//            }
//        }
//        return map.values().stream().sorted(Comparator.comparing(TimeSeriesPoint::getTs)).collect(Collectors.toList());
//    }
//
//    @Override
//    public List<TimeSeriesPoint> getUserTrafficTrend(Long userId, LocalDateTime from, LocalDateTime to) {
//        List<MinuteTrafficStats> userTrafficTrend = minuteTrafficStatsRepository.findUserTrafficTrend(userId, from, to);
//        Map<LocalDateTime, TimeSeriesPoint> map = new HashMap<>();
//        for (MinuteTrafficStats m : userTrafficTrend) {
//            TimeSeriesPoint timeSeriesPoint = map.get(m.getMinuteTime());
//            if (timeSeriesPoint == null) {
//                map.put(m.getMinuteTime(), toTimeSeriesPoint(m));
//            } else {
//                timeSeriesPoint.setByteIn(timeSeriesPoint.getByteIn() + m.getByteIn());
//                timeSeriesPoint.setByteOut(timeSeriesPoint.getByteOut() + m.getByteOut());
//            }
//        }
//        return map.values().stream().sorted(Comparator.comparing(TimeSeriesPoint::getTs)).collect(Collectors.toList());
//    }
//
//    @Override
//    public int cleanupExpiredMinuteTrafficStats() {
//        LocalDateTime oneMonthAgo = LocalDateTime.now().minusMonths(1);
//        minuteTrafficStatsRepository.deleteByMinuteTimeBefore(oneMonthAgo);
//        return 0;
//    }
//
//    @Override
//    public long countExpiredMinuteTrafficStats() {
//        LocalDateTime oneMonthAgo = LocalDateTime.now().minusMonths(1);
//        return minuteTrafficStatsRepository.countByMinuteTimeBefore(oneMonthAgo);
//    }
//
//    private TimeSeriesPoint toTimeSeriesPoint(MinuteTrafficStats minuteTrafficStats) {
//        return new TimeSeriesPoint(minuteTrafficStats.getMinuteTime(), minuteTrafficStats.getByteIn(),  minuteTrafficStats.getByteOut());
//    }
//
//    private LocalDateTime toLocalDateTime(java.time.Instant instant) {
//        if (instant == null) return LocalDateTime.now();
//        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
//    }
//
//    private AccessLogListItem toListItem(AccessLogEntity e) {
//        return new AccessLogListItem(
//                 e.getTs(), e.getRequestId(), e.getUserId(), e.getUsername(), e.getProxyName(),
//                e.getInboundId(), e.getClientIp(), e.getStatus(), e.getBytesIn(), e.getBytesOut(), e.getRequestDurationMs(),
//                e.getOriginalTargetHost(), e.getRewriteTargetHost(), e.getSrcGeoCountry(), e.getDstGeoCountry()
//        );
//    }
//
//    private AccessLogDetail toDetail(AccessLogEntity e) {
//        return new AccessLogDetail(
//                e.getId(), e.getTs(), e.getRequestId(), e.getUserId(), e.getUsername(), e.getProxyName(), e.getInboundId(),
//                e.getClientIp(), e.getClientPort(), e.getSrcGeoCountry(), e.getSrcGeoCity(), e.getOriginalTargetHost(), e.getOriginalTargetIP(),
//                e.getOriginalTargetPort(), e.getRewriteTargetHost(), e.getRewriteTargetPort(), e.getDstGeoCountry(), e.getDstGeoCity(),
//                e.getInboundProtocolType(), e.getOutboundProtocolType(), e.getRoutePolicyName(), e.getRoutePolicyId(), e.getBytesIn(), e.getBytesOut(),
//                e.getStatus(), e.getErrorCode(), e.getErrorMsg(), e.getRequestDurationMs(), e.getDnsDurationMs(), e.getConnectDurationMs(), e.getConnectTargetDurationMs()
//        );
//    }
//
//    private Specification<AccessLogEntity> buildAccessLogSpec(AccessLogQueryRequest req) {
//        Specification<AccessLogEntity> spec = (root, query, cb) -> cb.conjunction();
//        LocalDateTime from = parseDate(req.getFrom());
//        LocalDateTime to = parseDate(req.getTo());
//        if (from != null) spec = spec.and((root, cq, cb) -> cb.greaterThanOrEqualTo(root.get("ts"), from));
//        if (to != null) spec = spec.and((root, cq, cb) -> cb.lessThanOrEqualTo(root.get("ts"), to));
//        if (req.getUserId() != null) spec = spec.and((r, cq, cb) -> cb.equal(r.get("userId"), req.getUserId()));
//        if (notBlank(req.getUsername())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("username"), req.getUsername()));
//        if (notBlank(req.getProxyName())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("proxyName"), req.getProxyName()));
//        if (req.getInboundId() != null) spec = spec.and((r, cq, cb) -> cb.equal(r.get("inboundId"), req.getInboundId()));
//        if (notBlank(req.getClientIp())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("clientIp"), req.getClientIp()));
//        if (req.getStatus() != null) spec = spec.and((r, cq, cb) -> cb.equal(r.get("status"), req.getStatus()));
//        if (notBlank(req.getProtocol())) {
//            if ("inbound".equalsIgnoreCase(req.getProtocol())) {
//                spec = spec.and((r, cq, cb) -> cb.isNotNull(r.get("inboundProtocolType")));
//            } else if ("outbound".equalsIgnoreCase(req.getProtocol())) {
//                spec = spec.and((r, cq, cb) -> cb.isNotNull(r.get("outboundProtocolType")));
//            }
//        }
//        if (req.getRoutePolicyId() != null) spec = spec.and((r, cq, cb) -> cb.equal(r.get("routePolicyId"), req.getRoutePolicyId()));
//        if (notBlank(req.getSrcGeoCountry())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("srcGeoCountry"), req.getSrcGeoCountry()));
//        if (notBlank(req.getSrcGeoCity())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("srcGeoCity"), req.getSrcGeoCity()));
//        if (notBlank(req.getDstGeoCountry())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("dstGeoCountry"), req.getDstGeoCountry()));
//        if (notBlank(req.getDstGeoCity())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("dstGeoCity"), req.getDstGeoCity()));
//        if (notBlank(req.getHost())) {
//            spec = spec.and((r, cq, cb) -> cb.or(
//                    cb.equal(r.get("originalTargetHost"), req.getHost()),
//                    cb.equal(r.get("rewriteTargetHost"), req.getHost())
//            ));
//        }
//        if (notBlank(req.getOriginalTargetHost())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("originalTargetHost"), req.getOriginalTargetHost()));
//        if (notBlank(req.getRewriteTargetHost())) spec = spec.and((r, cq, cb) -> cb.equal(r.get("rewriteTargetHost"), req.getRewriteTargetHost()));
//        if (notBlank(req.getQ())) {
//            String like = "%" + req.getQ().trim() + "%";
//            spec = spec.and((r, cq, cb) -> cb.or(
//                    cb.like(r.get("clientIp"), like),
//                    cb.like(r.get("username"), like),
//                    cb.like(r.get("proxyName"), like),
//                    cb.like(r.get("originalTargetHost"), like),
//                    cb.like(r.get("rewriteTargetHost"), like),
//                    cb.like(r.get("errorMsg"), like)
//            ));
//        }
//        return spec;
//    }
//
//    private Sort parseSort(String sort) {
//        if (sort == null || sort.isBlank()) return Sort.by(Sort.Direction.DESC, "ts");
//        String[] parts = sort.split(",");
//        if (parts.length == 2) {
//            Sort.Direction dir = "asc".equalsIgnoreCase(parts[1]) ? Sort.Direction.ASC : Sort.Direction.DESC;
//            return Sort.by(dir, parts[0]);
//        }
//        return Sort.by(Sort.Direction.DESC, sort);
//    }
//
//    private boolean notBlank(String s) { return s != null && !s.isBlank(); }
//
//    private long nullSafe(Long v) { return v == null ? 0L : v; }
//
//    private LocalDateTime parseDate(String s) {
//        if (s == null || s.isBlank()) return null;
//        try {
//            if (s.matches("^\\d{13}$")) {
//                long ms = Long.parseLong(s);
//                return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault());
//            }
//            if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
//                int y = Integer.parseInt(s.substring(0, 4));
//                int m = Integer.parseInt(s.substring(5, 7));
//                int d = Integer.parseInt(s.substring(8, 10));
//                return LocalDateTime.of(y, m, d, 0, 0, 0);
//            }
//            try {
//                return LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
//            } catch (DateTimeParseException ignored) {}
//            java.time.Instant instant = java.time.Instant.parse(s);
//            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
//        } catch (DateTimeParseException | NumberFormatException ex) {
//            return null;
//        }
//    }
//}