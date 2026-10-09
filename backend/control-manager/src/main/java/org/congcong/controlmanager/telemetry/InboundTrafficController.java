package org.congcong.controlmanager.telemetry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/observability/v1/traffic/inbound")
public class InboundTrafficController {
    private final InboundTrafficService service;

    @GetMapping("/{inboundId}/month")
    public InboundTrafficService.MonthlyTraffic monthly(@PathVariable long inboundId,
            @RequestParam String workerId, @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "Asia/Shanghai") String timeZone) {
        try { return service.monthly(inboundId, workerId, month, timeZone); }
        catch (IllegalArgumentException | DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入站、节点、月份或时区无效");
        } catch (RuntimeException e) {
            log.warn("Inbound monthly traffic unavailable: {}", e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "新日志流量查询暂不可用");
        }
    }
}
