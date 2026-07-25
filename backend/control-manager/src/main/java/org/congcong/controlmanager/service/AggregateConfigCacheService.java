package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.AggregateConfigResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class AggregateConfigCacheService {

    private final AggregateConfigService aggregateConfigService;

    @Cacheable(value = "aggregateConfig", key = "#workerId")
    public AggregateConfigResponse getAggregateConfig(String workerId) {
        return aggregateConfigService.getAggregateConfig(workerId);
    }

    public AggregateConfigResponse getRedactedAggregateConfig(String workerId) {
        return aggregateConfigService.getRedactedAggregateConfig(workerId);
    }

}
