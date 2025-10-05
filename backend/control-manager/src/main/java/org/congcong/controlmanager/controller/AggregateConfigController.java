package org.congcong.controlmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.controlmanager.service.AggregateConfigCacheService;
import org.congcong.controlmanager.service.AggregateConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 聚合配置控制器
 * 实现配置分发机制，支持HTTP 304缓存机制
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
@Slf4j
public class AggregateConfigController {


    private final AggregateConfigCacheService aggregateConfigCacheService;

    /**
     * 获取聚合配置
     * 支持HTTP 304缓存机制
     * 
     * @param ifNoneMatch 客户端提供的ETag值，用于缓存验证
     * @return 聚合配置响应或304状态码
     */
    @GetMapping("/aggregate")
    public ResponseEntity<AggregateConfigResponse> getAggregateConfig(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        
        try {
            log.debug("获取聚合配置请求，If-None-Match: {}", ifNoneMatch);
            
            // 获取当前配置哈希值
            AggregateConfigResponse aggregateConfig = aggregateConfigCacheService.getAggregateConfig();


            String currentConfigHash = aggregateConfig.getConfigHash();
            
            // 检查客户端缓存是否有效
            if (ifNoneMatch != null && ifNoneMatch.equals("\"" + currentConfigHash + "\"")) {
                log.debug("配置未变更，返回304状态码");
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                        .eTag("\"" + currentConfigHash + "\"")
                        .build();
            }
            
            // 获取聚合配置

            log.debug("返回聚合配置，ETag: {}", currentConfigHash);
            
            // 返回配置并设置缓存头
            return ResponseEntity.ok()
                    .eTag("\"" + currentConfigHash + "\"")
                    .lastModified(aggregateConfig.getGeneratedAt().atZone(java.time.ZoneId.systemDefault()).toInstant())
                    .body(aggregateConfig);
                    
        } catch (Exception e) {
            log.error("获取聚合配置失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

}