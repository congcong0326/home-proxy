package org.congcong.controlmanager.telemetry;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/observability/v1")
@RequiredArgsConstructor
public class TelemetryController {
    private final TelemetryStore store;
    private final TelemetryProperties properties;

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(required = false) String workerId) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(Map.of("schemaVersion", 1, "ingestEnabled", properties.isIngestEnabled(),
                "queryEnabled", properties.isQueryEnabled(), "delivery", "BEST_EFFORT_WITH_ACK",
                "scope", "STAGE_A_INFRASTRUCTURE", "authenticationAnalysis", "GEOIP_CONTRACT_ONLY"));
        if (workerId != null && !workerId.isBlank()) result.put("worker", store.workerStatus(workerId));
        return result;
    }

    @GetMapping("/{type}")
    public List<Map<String, Object>> query(@PathVariable String type, @RequestParam String workerId,
            @RequestParam Instant from, @RequestParam Instant to, @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(required = false) String connectionId) {
        try { return store.query(TelemetrySchema.fromPath(type), workerId, from, to, limit, offset, connectionId); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()); }
    }

    @GetMapping("/{type}/page")
    public TelemetryStore.AuditPage page(@PathVariable String type, @ModelAttribute TelemetryQueryRequest request) {
        try { return store.page(TelemetrySchema.fromPath(type), request); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "V1_QUERY_UNAVAILABLE"); }
    }

    @GetMapping("/{type}/{recordId}")
    public Map<String, Object> detail(@PathVariable String type, @PathVariable String recordId,
                                     @RequestParam String workerId, @RequestParam Instant from, @RequestParam Instant to) {
        try {
            var request = new TelemetryQueryRequest();
            request.setWorkerId(workerId); request.setFrom(from); request.setTo(to);
            var row = store.detail(TelemetrySchema.fromPath(type), recordId, request);
            if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record absent from selected Worker/time scope");
            return row;
        } catch (ResponseStatusException e) { throw e; }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "V1_QUERY_UNAVAILABLE"); }
    }

}
