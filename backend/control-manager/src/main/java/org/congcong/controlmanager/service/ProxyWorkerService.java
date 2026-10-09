package org.congcong.controlmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.ProxyWorkerDTO;
import org.congcong.common.dto.WorkerStatusSnapshot;
import org.congcong.common.dto.WorkerMetricsDTO;
import org.congcong.common.dto.WorkerTokenResponse;
import org.congcong.common.enums.LogLevel;
import org.congcong.controlmanager.dto.WorkerCreateRequest;
import org.congcong.controlmanager.dto.WorkerUpdateRequest;
import org.congcong.controlmanager.entity.ProxyWorker;
import org.congcong.controlmanager.entity.WorkerStatus;
import org.congcong.controlmanager.repository.InboundConfigRepository;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.congcong.controlmanager.repository.WorkerStatusRepository;
import org.congcong.controlmanager.repository.WorkerTaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProxyWorkerService {
    private static final String TOKEN_PREFIX = "hpw_";
    private static final long ONLINE_THRESHOLD_SECONDS = 10L;
    private static final ObjectMapper METRICS_MAPPER = new ObjectMapper();

    private final ProxyWorkerRepository proxyWorkerRepository;
    private final WorkerStatusRepository workerStatusRepository;
    private final WorkerTaskRepository workerTaskRepository;
    private final InboundConfigRepository inboundConfigRepository;
    private final CacheManager cacheManager;

    @Transactional(readOnly = true)
    public List<ProxyWorkerDTO> list() {
        List<ProxyWorker> workers = proxyWorkerRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(ProxyWorker::getWorkerId))
                .toList();
        Map<String, WorkerStatus> statuses = workerStatusRepository.findAllById(
                        workers.stream().map(ProxyWorker::getWorkerId).toList())
                .stream()
                .collect(Collectors.toMap(WorkerStatus::getWorkerId, Function.identity()));
        return workers.stream()
                .map(worker -> toDto(worker, statuses.get(worker.getWorkerId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProxyWorkerDTO get(String workerId) {
        return toDto(requireWorker(workerId));
    }

    @Transactional
    public WorkerTokenResponse create(WorkerCreateRequest request) {
        if (proxyWorkerRepository.existsById(request.getWorkerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "节点已存在: " + request.getWorkerId());
        }
        ProxyWorker worker = new ProxyWorker();
        worker.setWorkerId(request.getWorkerId());
        worker.setDisplayName(request.getDisplayName());
        worker.setDescription(request.getDescription());
        worker.setEnabled(true);
        worker.setLogReportingEnabled(request.getLogReportingEnabled() == null || request.getLogReportingEnabled());
        worker.setLogLevel(request.getLogLevel() == null ? LogLevel.INFO : request.getLogLevel());
        configureManagementConnection(worker, request.getManagementUrl(), request.getManagementToken(), true);
        ProxyWorker saved = proxyWorkerRepository.save(worker);
        evictConfigAfterCommit(saved.getWorkerId());
        return tokenResponse(saved);
    }

    @Transactional
    public ProxyWorkerDTO update(String workerId, WorkerUpdateRequest request) {
        ProxyWorker worker = requireWorker(workerId);
        worker.setDisplayName(request.getDisplayName());
        worker.setDescription(request.getDescription());
        if (request.getEnabled() != null) worker.setEnabled(request.getEnabled());
        if (request.getLogReportingEnabled() != null) worker.setLogReportingEnabled(request.getLogReportingEnabled());
        if (request.getLogLevel() != null) worker.setLogLevel(request.getLogLevel());
        configureManagementConnection(worker, request.getManagementUrl(), request.getManagementToken(), false);
        ProxyWorker saved = proxyWorkerRepository.save(worker);
        evictConfigAfterCommit(workerId);
        return toDto(saved);
    }

    @Transactional
    public ProxyWorkerDTO disable(String workerId) {
        ProxyWorker worker = requireWorker(workerId);
        worker.setEnabled(false);
        return toDto(proxyWorkerRepository.save(worker));
    }

    @Transactional
    public void delete(String workerId) {
        requireWorker(workerId);
        long inbounds = inboundConfigRepository.countByWorkerId(workerId);
        long tasks = workerTaskRepository.countByWorkerId(workerId);
        long statuses = workerStatusRepository.countByWorkerId(workerId);
        if (inbounds + tasks + statuses > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "节点仍被引用: inbounds=" + inbounds + ", tasks=" + tasks + ", statuses=" + statuses);
        }
        proxyWorkerRepository.deleteById(workerId);
    }

    private ProxyWorker requireWorker(String workerId) {
        return proxyWorkerRepository.findById(workerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "节点不存在: " + workerId));
    }

    private void evictConfigAfterCommit(String workerId) {
        Cache cache = cacheManager.getCache("aggregateConfig");
        if (cache == null) return;
        // A poll during the transaction must not repopulate the cache with the old row.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.evict(workerId);
                }
            });
        } else {
            cache.evict(workerId);
        }
    }

    private WorkerTokenResponse tokenResponse(ProxyWorker worker) {
        WorkerTokenResponse response = new WorkerTokenResponse();
        response.setWorker(toDto(worker));
        return response;
    }

    private ProxyWorkerDTO toDto(ProxyWorker worker) {
        return toDto(worker, workerStatusRepository.findById(worker.getWorkerId()).orElse(null));
    }

    private ProxyWorkerDTO toDto(ProxyWorker worker, WorkerStatus status) {
        ProxyWorkerDTO dto = new ProxyWorkerDTO();
        dto.setWorkerId(worker.getWorkerId());
        dto.setDisplayName(worker.getDisplayName());
        dto.setEnabled(worker.getEnabled());
        dto.setLogReportingEnabled(worker.getLogReportingEnabled() == null || worker.getLogReportingEnabled());
        dto.setLogLevel(worker.getLogLevel() == null ? LogLevel.INFO : worker.getLogLevel());
        dto.setDescription(worker.getDescription());
        dto.setManagementUrl(worker.getManagementUrl());
        dto.setManagementToken(worker.getManagementToken());
        dto.setManagementTokenPresent(worker.getManagementToken() != null && !worker.getManagementToken().isBlank());
        dto.setCreatedAt(worker.getCreatedAt());
        dto.setUpdatedAt(worker.getUpdatedAt());
        if (status != null) dto.setStatus(toStatus(status));
        return dto;
    }

    private void configureManagementConnection(ProxyWorker worker, String suppliedUrl, String suppliedToken,
                                               boolean creating) {
        boolean urlProvided = suppliedUrl != null;
        boolean tokenProvided = suppliedToken != null;
        if (!urlProvided && !tokenProvided && !creating) return;

        String url = urlProvided ? normalizeManagementUrl(suppliedUrl) : worker.getManagementUrl();
        String token = tokenProvided ? trimToNull(suppliedToken) : worker.getManagementToken();
        if (creating && (url == null || token == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Worker WSS 地址和 Worker 管理 Token 必须同时配置");
        }
        if (url == null && token == null) {
            worker.setManagementUrl(null);
            worker.setManagementToken(null);
            return;
        }
        if (url == null || token == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "管理地址和管理 Token 必须同时配置");
        }
        if (!token.startsWith(TOKEN_PREFIX) || token.length() <= TOKEN_PREFIX.length()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "管理 Token 格式不正确");
        }
        worker.setManagementUrl(url);
        worker.setManagementToken(token);
    }

    private String normalizeManagementUrl(String value) {
        String url = trimToNull(value);
        if (url == null) return null;
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (!"wss".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "管理地址必须是有效的 wss:// 地址");
        }
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private WorkerStatusSnapshot toStatus(WorkerStatus status) {
        WorkerStatusSnapshot dto = new WorkerStatusSnapshot();
        dto.setWorkerId(status.getWorkerId());
        dto.setHostname(status.getHostname());
        dto.setStartedAt(status.getStartedAt());
        dto.setLastSeenAt(status.getLastSeenAt());
        dto.setFetchedConfigHash(status.getFetchedConfigHash());
        dto.setAppliedConfigHash(status.getAppliedConfigHash());
        dto.setApplySuccess(status.getApplySuccess());
        dto.setApplyError(status.getApplyError());
        dto.setAppliedAt(status.getAppliedAt());
        dto.setUptimeSeconds(status.getUptimeSeconds());
        dto.setHeapUsedBytes(status.getHeapUsedBytes());
        dto.setHeapMaxBytes(status.getHeapMaxBytes());
        dto.setRunningInboundCount(status.getRunningInboundCount());
        dto.setActiveConnectionCount(status.getActiveConnectionCount());
        if (status.getMetricsJson() != null) {
            try {
                dto.setMetrics(METRICS_MAPPER.readValue(status.getMetricsJson(), WorkerMetricsDTO.class));
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalidMetrics) {
                throw new IllegalStateException("stored worker metrics are invalid", invalidMetrics);
            }
        }
        dto.setOnline(status.getLastSeenAt() != null && status.getLastSeenAt().isAfter(LocalDateTime.now().minusSeconds(ONLINE_THRESHOLD_SECONDS)));
        return dto;
    }
}
