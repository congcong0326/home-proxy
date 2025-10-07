package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.AccessLog;
import org.congcong.common.dto.AuthLog;
import org.congcong.controlmanager.entity.AccessLogEntity;
import org.congcong.controlmanager.entity.AuthLogEntity;
import org.congcong.controlmanager.repository.AccessLogRepository;
import org.congcong.controlmanager.repository.AuthLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

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
}