package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.congcong.common.enums.RateLimitScopeType;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.dto.RateLimitCreateRequest;
import org.congcong.controlmanager.dto.RateLimitDTO;
import org.congcong.controlmanager.dto.RateLimitUpdateRequest;
import org.congcong.controlmanager.service.RateLimitService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;


import java.util.List;

/**
 * 限流策略控制器
 */
@RestController
@RequestMapping("/api/rate-limits")
@RequiredArgsConstructor
@Validated
public class RateLimitController {

    private final RateLimitService rateLimitService;

    /**
     * 分页查询限流策略列表
     */
    @GetMapping
    public ResponseEntity<PageResponse<RateLimitDTO>> getRateLimits(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) RateLimitScopeType scopeType,
            @RequestParam(required = false) Boolean enabled) {

        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(direction, sortBy));

        PageResponse<RateLimitDTO> response = rateLimitService.getRateLimits(pageable, scopeType, enabled);
        return ResponseEntity.ok(response);
    }

    /**
     * 根据ID查询限流策略详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<RateLimitDTO> getRateLimitById(
            @PathVariable Long id) {
        return rateLimitService.getRateLimitById(id)
                .map(rateLimit -> ResponseEntity.ok(rateLimit))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "限流策略不存在: " + id));
    }

    /**
     * 创建限流策略
     */
    @PostMapping
    public ResponseEntity<RateLimitDTO> createRateLimit(
            @Valid @RequestBody RateLimitCreateRequest request) {
        RateLimitDTO createdRateLimit = rateLimitService.createRateLimit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdRateLimit);
    }

    /**
     * 更新限流策略
     */
    @PutMapping("/{id}")
    public ResponseEntity<RateLimitDTO> updateRateLimit(
            @PathVariable Long id,
            @Valid @RequestBody RateLimitUpdateRequest request) {
        RateLimitDTO updatedRateLimit = rateLimitService.updateRateLimit(id, request);
        return ResponseEntity.ok(updatedRateLimit);
    }

    /**
     * 删除限流策略
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRateLimit(
            @PathVariable Long id) {
        rateLimitService.deleteRateLimit(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 根据范围类型查询限流策略
     */
    @GetMapping("/scope/{scopeType}")
    public ResponseEntity<List<RateLimitDTO>> getRateLimitsByScopeType(
            @PathVariable RateLimitScopeType scopeType) {
        List<RateLimitDTO> rateLimits = rateLimitService.getRateLimitsByScopeType(scopeType);
        return ResponseEntity.ok(rateLimits);
    }

    /**
     * 根据启用状态查询限流策略
     */
    @GetMapping("/enabled/{enabled}")
    public ResponseEntity<List<RateLimitDTO>> getRateLimitsByEnabled(
            @PathVariable Boolean enabled) {
        List<RateLimitDTO> rateLimits = rateLimitService.getRateLimitsByEnabled(enabled);
        return ResponseEntity.ok(rateLimits);
    }


}