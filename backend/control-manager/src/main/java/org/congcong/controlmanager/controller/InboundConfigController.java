package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.congcong.common.enums.ProtocolType;
import org.congcong.controlmanager.dto.InboundConfigCreateRequest;
import org.congcong.controlmanager.dto.InboundConfigDTO;
import org.congcong.controlmanager.dto.InboundConfigUpdateRequest;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.service.InboundConfigService;
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
 * 入站配置控制器
 */
@RestController
@RequestMapping("/api/inbounds")
@RequiredArgsConstructor
@Validated
public class InboundConfigController {

    private final InboundConfigService inboundConfigService;

    /**
     * 分页查询入站配置列表
     */
    @GetMapping
    public ResponseEntity<PageResponse<InboundConfigDTO>> getInboundConfigs(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) ProtocolType protocol,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) Boolean tlsEnabled,
            @RequestParam(required = false) Integer status) {

        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(direction, sortBy));

        PageResponse<InboundConfigDTO> response = inboundConfigService.getInboundConfigs(
                pageable, protocol, port, tlsEnabled, status);
        return ResponseEntity.ok(response);
    }

    /**
     * 根据ID查询入站配置详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<InboundConfigDTO> getInboundConfigById(
            @PathVariable Long id) {
        return inboundConfigService.getInboundConfigById(id)
                .map(inboundConfig -> ResponseEntity.ok(inboundConfig))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "入站配置不存在: " + id));
    }

    /**
     * 创建入站配置
     */
    @PostMapping
    public ResponseEntity<InboundConfigDTO> createInboundConfig(
            @Valid @RequestBody InboundConfigCreateRequest request) {
        InboundConfigDTO createdInboundConfig = inboundConfigService.createInboundConfig(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdInboundConfig);
    }

    /**
     * 更新入站配置
     */
    @PutMapping("/{id}")
    public ResponseEntity<InboundConfigDTO> updateInboundConfig(
            @PathVariable Long id,
            @Valid @RequestBody InboundConfigUpdateRequest request) {
        InboundConfigDTO updatedInboundConfig = inboundConfigService.updateInboundConfig(id, request);
        return ResponseEntity.ok(updatedInboundConfig);
    }

    /**
     * 删除入站配置
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteInboundConfig(
            @PathVariable Long id) {
        inboundConfigService.deleteInboundConfig(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 根据协议类型查询入站配置
     */
    @GetMapping("/protocol/{protocol}")
    public ResponseEntity<List<InboundConfigDTO>> getInboundConfigsByProtocol(
            @PathVariable ProtocolType protocol) {
        List<InboundConfigDTO> inboundConfigs = inboundConfigService.getInboundConfigsByProtocol(protocol);
        return ResponseEntity.ok(inboundConfigs);
    }

    /**
     * 根据状态查询入站配置
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<List<InboundConfigDTO>> getInboundConfigsByStatus(
            @PathVariable Integer status) {
        List<InboundConfigDTO> inboundConfigs = inboundConfigService.getInboundConfigsByStatus(status);
        return ResponseEntity.ok(inboundConfigs);
    }
}