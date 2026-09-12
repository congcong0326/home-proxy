package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.common.dto.ProxyWorkerDTO;
import org.congcong.common.dto.WorkerStatusSnapshot;
import org.congcong.common.dto.WorkerTokenResponse;
import org.congcong.controlmanager.dto.WorkerCreateRequest;
import org.congcong.controlmanager.dto.WorkerUpdateRequest;
import org.congcong.controlmanager.service.AggregateConfigCacheService;
import org.congcong.controlmanager.service.ProxyWorkerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workers")
@RequiredArgsConstructor
public class ProxyWorkerController {
    private final ProxyWorkerService proxyWorkerService;
    private final AggregateConfigCacheService aggregateConfigCacheService;

    @GetMapping
    public List<ProxyWorkerDTO> list() { return proxyWorkerService.list(); }

    @PostMapping
    public ResponseEntity<WorkerTokenResponse> create(@Valid @RequestBody WorkerCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(proxyWorkerService.create(request));
    }

    @GetMapping("/{workerId}")
    public ProxyWorkerDTO get(@PathVariable String workerId) { return proxyWorkerService.get(workerId); }

    @PutMapping("/{workerId}")
    public ProxyWorkerDTO update(@PathVariable String workerId, @Valid @RequestBody WorkerUpdateRequest request) {
        return proxyWorkerService.update(workerId, request);
    }

    @DeleteMapping("/{workerId}")
    public ResponseEntity<Void> delete(@PathVariable String workerId) {
        proxyWorkerService.delete(workerId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{workerId}/disable")
    public ProxyWorkerDTO disable(@PathVariable String workerId) { return proxyWorkerService.disable(workerId); }

    @GetMapping("/{workerId}/status")
    public WorkerStatusSnapshot status(@PathVariable String workerId) {
        ProxyWorkerDTO worker = proxyWorkerService.get(workerId);
        if (worker.getStatus() == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "节点尚未上报状态");
        }
        return worker.getStatus();
    }

    @GetMapping("/{workerId}/effective-config")
    public AggregateConfigResponse effectiveConfig(@PathVariable String workerId) {
        return aggregateConfigCacheService.getRedactedAggregateConfig(workerId);
    }
}
