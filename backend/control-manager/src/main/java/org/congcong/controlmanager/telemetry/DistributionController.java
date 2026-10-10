package org.congcong.controlmanager.telemetry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/api/observability/v1/distribution")
@RequiredArgsConstructor
public class DistributionController {
    private final DistributionService service;
    private final CredentialDistributionService credentials;

    @GetMapping
    public Object distribution(@ModelAttribute DistributionQuery query) {
        try { return "auth_geo".equals(query.getDimension()) ? credentials.aggregate(query) : service.aggregate(query); }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Distribution query unavailable: {}", e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "访问分布暂不可用，请检查新版日志查询服务");
        }
    }
}
