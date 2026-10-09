package org.congcong.controlmanager.telemetry;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/observability/v1/overview")
@RequiredArgsConstructor
public class OverviewController {
    private final OverviewService service;
    @GetMapping
    public Map<String, Object> overview(@ModelAttribute OverviewQuery query) {
        try { return service.overview(query); }
        catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Overview query unavailable: {}", e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "CONCURRENCY_RANGE_TOO_DENSE".equals(e.getMessage()) ? "并发数据过密，请缩短时间范围" : "新版统计暂不可用，请检查新日志查询服务");
        }
    }
    @GetMapping("/live")
    public Map<String, Object> live(@ModelAttribute OverviewQuery query) {
        try { OverviewService.validate(query); return service.live(query); }
        catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "当前并发采样暂不可用");
        }
    }
}
