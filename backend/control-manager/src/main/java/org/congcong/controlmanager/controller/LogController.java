package org.congcong.controlmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.AccessLog;
import org.congcong.common.dto.AuthLog;
import org.congcong.controlmanager.dto.*;
import org.congcong.controlmanager.service.LogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@Validated
public class LogController {

    private final LogService logService;

    /**
     * 接收访问日志批量
     */
    @PostMapping("/access")
    public ResponseEntity<Map<String, Object>> ingestAccessLogs(@RequestBody List<AccessLog> logs) {
        int saved = logService.saveAccessLogs(logs);
        Map<String, Object> resp = new HashMap<>();
        resp.put("saved", saved);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
    }

//    /**
//     * 接收认证日志批量
//     */
//    @PostMapping("/auth")
//    public ResponseEntity<Map<String, Object>> ingestAuthLogs(@RequestBody List<AuthLog> logs) {
//        int saved = logService.saveAuthLogs(logs);
//        Map<String, Object> resp = new HashMap<>();
//        resp.put("saved", saved);
//        return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
//    }

    /**
     * 分页检索访问日志
     */
    @GetMapping("/access")
    public ResponseEntity<PageResponse<AccessLogListItem>> queryAccessLogs(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "proxyName", required = false) String proxyName,
            @RequestParam(value = "inboundId", required = false) Long inboundId,
            @RequestParam(value = "clientIp", required = false) String clientIp,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "protocol", required = false) String protocol,
            @RequestParam(value = "routePolicyId", required = false) Long routePolicyId,
            @RequestParam(value = "routePolicyName", required = false) String routePolicyName,
            @RequestParam(value = "srcGeoCountry", required = false) String srcGeoCountry,
            @RequestParam(value = "srcGeoCity", required = false) String srcGeoCity,
            @RequestParam(value = "dstGeoCountry", required = false) String dstGeoCountry,
            @RequestParam(value = "dstGeoCity", required = false) String dstGeoCity,
            @RequestParam(value = "host", required = false) String host,
            @RequestParam(value = "originalTargetHost", required = false) String originalTargetHost,
            @RequestParam(value = "rewriteTargetHost", required = false) String rewriteTargetHost,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "sort", required = false) String sort
    ) {
        AccessLogQueryRequest req = new AccessLogQueryRequest(from, to, userId, username, proxyName, inboundId, clientIp, status,
                protocol, routePolicyId, routePolicyName, srcGeoCountry, srcGeoCity, dstGeoCountry, dstGeoCity, host, originalTargetHost, rewriteTargetHost, q,
                page, size, sort);
        PageResponse<AccessLogListItem> resp = logService.queryAccessLogs(req);
        return ResponseEntity.ok(resp);
    }

    /**
     * 访问日志详情
     */
    @GetMapping("/access/{id}")
    public ResponseEntity<AccessLogDetail> getAccessLogDetail(@PathVariable("id") String id) {
        return logService.getAccessLogDetail(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }


    

    /**
     * 基于日度聚合表的 TopN 查询（支持时间区间）
     *
     * 入参说明：
     * - `from` / `to`：时间范围（支持 `yyyy-MM-dd` 或 13位毫秒时间戳），均为空则默认统计当月。
     * - `dimension`：Top 维度，支持：
     *   - `users`：按用户（用户名优先，缺省使用用户ID）
     *   - `apps`：按应用（原始目标主机 `originalTargetHost`）
     *   - `user_apps`：按用户@应用（用户名@原始目标主机）
     *   - `src_geo`：按来源地理位置（国家/城市）
     *   - `dst_geo`：按目的地理位置（国家/城市）
     *   默认值为 `apps`。
     * - `metric`：统计指标，支持：
     *   - `requests`：请求数
     *   - `bytes`：流量字节数（入+出）
     *   默认值为 `requests`。
     * - `limit`：返回条目数，默认 10，最大 100。
     */
    @GetMapping("/access/aggregate/daily/top")
    public ResponseEntity<List<org.congcong.controlmanager.dto.TopItem>> aggregateDailyTop(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "dimension", required = false) String dimension,
            @RequestParam(value = "metric", required = false) String metric,
            @RequestParam(value = "limit", required = false, defaultValue = "10") Integer limit,
            @RequestParam(value = "userId", required = false) Long userId
    ) {
        List<org.congcong.controlmanager.dto.TopItem> items = logService.aggregateDailyTopRange(from, to, dimension, metric, limit == null ? 10 : limit, userId);
        return ResponseEntity.ok(items);
    }

    /**
     * 获取全局分钟级流量趋势
     */
    @GetMapping("/traffic/minute/global")
    public ResponseEntity<List<TimeSeriesPoint>> getGlobalMinuteTrafficTrend(
            @RequestParam("startTime") LocalDateTime startTime,
            @RequestParam("endTime") LocalDateTime endTime) {
        List<TimeSeriesPoint> trend = logService.getGlobalTrafficTrend(startTime, endTime);
        return ResponseEntity.ok(trend);
    }

    /**
     * 获取指定用户的分钟级流量趋势
     */
    @GetMapping("/traffic/minute/user/{userId}")
    public ResponseEntity<List<TimeSeriesPoint>> getUserMinuteTrafficTrend(
            @PathVariable Long userId,
            @RequestParam("startTime") LocalDateTime startTime,
            @RequestParam("endTime") LocalDateTime endTime) {
        List<TimeSeriesPoint> trend = logService.getUserTrafficTrend(userId, startTime, endTime);
        return ResponseEntity.ok(trend);
    }

    /**
     * 获取指定入站本月（或指定月份）的上下行流量
     */
    @GetMapping("/traffic/inbound/{inboundId}/month")
    public ResponseEntity<InboundTrafficDTO> getInboundMonthlyTraffic(
            @PathVariable("inboundId") Long inboundId,
            @RequestParam(value = "month", required = false) String month) {
        YearMonth ym = null;
        if (month != null && !month.isBlank()) {
            try {
                ym = YearMonth.parse(month);
            } catch (Exception e) {
                log.warn("Invalid month format {}, expected yyyy-MM, fallback to current", month);
            }
        }
        InboundTrafficDTO dto = logService.getInboundMonthlyTraffic(inboundId, ym);
        return ResponseEntity.ok(dto);
    }
}
