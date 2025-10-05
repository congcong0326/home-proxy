package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.AggregateConfigResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class AggregateConfigCacheService {

    private final AggregateConfigService aggregateConfigService;

    @Cacheable(value = "aggregateConfig", key = "'config'")
    public AggregateConfigResponse getAggregateConfig() {
        return aggregateConfigService.getAggregateConfig();
    }

}
