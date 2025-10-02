package org.congcong.controlmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.AggregateConfigResponse;
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

    private final AggregateConfigService aggregateConfigService;

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
            String currentConfigHash = aggregateConfigService.getCurrentConfigHash();
            
            // 检查客户端缓存是否有效
            if (ifNoneMatch != null && ifNoneMatch.equals("\"" + currentConfigHash + "\"")) {
                log.debug("配置未变更，返回304状态码");
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                        .eTag("\"" + currentConfigHash + "\"")
                        .build();
            }
            
            // 获取聚合配置
            AggregateConfigResponse response = aggregateConfigService.getAggregateConfig();
            
            log.debug("返回聚合配置，ETag: {}", currentConfigHash);
            
            // 返回配置并设置缓存头
            return ResponseEntity.ok()
                    .eTag("\"" + currentConfigHash + "\"")
                    .lastModified(response.getGeneratedAt().atZone(java.time.ZoneId.systemDefault()).toInstant())
                    .body(response);
                    
        } catch (Exception e) {
            log.error("获取聚合配置失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取当前配置哈希值
     * 用于客户端检查配置是否有更新
     * 
     * @return 当前配置的哈希值
     */
    @GetMapping("/hash")
    public ResponseEntity<String> getCurrentConfigHash() {
        try {
            String configHash = aggregateConfigService.getCurrentConfigHash();
            return ResponseEntity.ok(configHash);
        } catch (Exception e) {
            log.error("获取配置哈希值失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}