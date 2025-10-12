package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.config.WolConfig;
import org.congcong.controlmanager.dto.WolConfigCreateRequest;
import org.congcong.controlmanager.dto.WolConfigDTO;
import org.congcong.controlmanager.dto.WolConfigUpdateRequest;
import org.congcong.controlmanager.entity.PcStatus;
import org.congcong.controlmanager.service.IpMonitor;
import org.congcong.controlmanager.service.WolConfigService;
import org.congcong.controlmanager.service.WolService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * WOL配置和控制API
 */
@Slf4j
@RestController
@RequestMapping("/api/wol")
@RequiredArgsConstructor
@Validated
public class WolController {

    private final WolConfigService wolConfigService;
    private final WolService wolService;
    private final IpMonitor ipMonitor;

    /**
     * 获取所有WOL配置
     */
    @GetMapping("/configs")
    public ResponseEntity<List<WolConfigDTO>> getAllConfigs() {
        List<WolConfig> configs = wolConfigService.getAllConfigs();
        List<WolConfigDTO> dtos = configs.stream()
                .map(this::convertToDTO)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * 获取指定ID的WOL配置
     */
    @GetMapping("/configs/{id}")
    public ResponseEntity<WolConfigDTO> getConfigById(@PathVariable Long id) {
        return wolConfigService.getConfigById(id)
                .map(config -> ResponseEntity.ok(convertToDTO(config)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 创建WOL配置
     */
    @PostMapping("/configs")
    public ResponseEntity<WolConfigDTO> createConfig(@Valid @RequestBody WolConfigCreateRequest request) {
        WolConfig config = convertFromCreateRequest(request);
        WolConfig savedConfig = wolConfigService.createConfig(config);
        return ResponseEntity.status(HttpStatus.CREATED).body(convertToDTO(savedConfig));
    }

    /**
     * 更新WOL配置
     */
    @PutMapping("/configs/{id}")
    public ResponseEntity<WolConfigDTO> updateConfig(
            @PathVariable Long id,
            @Valid @RequestBody WolConfigUpdateRequest request) {
        WolConfig config = convertFromUpdateRequest(request);
        WolConfig updatedConfig = wolConfigService.updateConfig(id, config);
        return ResponseEntity.ok(convertToDTO(updatedConfig));
    }

    /**
     * 删除WOL配置
     */
    @DeleteMapping("/configs/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id) {
        wolConfigService.deleteConfig(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 启用WOL配置
     */
    @PostMapping("/configs/{id}/enable")
    public ResponseEntity<WolConfigDTO> enableConfig(@PathVariable Long id) {
        WolConfig config = wolConfigService.enableConfig(id);
        return ResponseEntity.ok(convertToDTO(config));
    }

    /**
     * 禁用WOL配置
     */
    @PostMapping("/configs/{id}/disable")
    public ResponseEntity<WolConfigDTO> disableConfig(@PathVariable Long id) {
        WolConfig config = wolConfigService.disableConfig(id);
        return ResponseEntity.ok(convertToDTO(config));
    }

    /**
     * 获取所有设备状态
     */
    @GetMapping("/status")
    public ResponseEntity<List<PcStatus>> getAllPcStatus() {
        List<PcStatus> statuses = ipMonitor.getAllPcStatus();
        return ResponseEntity.ok(statuses);
    }

    /**
     * 获取指定IP的设备状态
     */
    @GetMapping("/status/{ip}")
    public ResponseEntity<Map<String, Object>> getIpStatus(@PathVariable String ip) {
        Boolean online = ipMonitor.getIpStatus(ip);
        WolConfig config = ipMonitor.getByIp(ip);
        
        Map<String, Object> result = Map.of(
                "ip", ip,
                "online", online,
                "config", config != null ? convertToDTO(config) : null
        );
        
        return ResponseEntity.ok(result);
    }

    /**
     * 手动检测指定IP状态
     */
    @PostMapping("/status/{ip}/check")
    public ResponseEntity<Map<String, Object>> checkIpStatus(@PathVariable String ip) {
        boolean online = ipMonitor.checkIpStatus(ip);
        Map<String, Object> result = Map.of(
                "ip", ip,
                "online", online,
                "timestamp", System.currentTimeMillis()
        );
        return ResponseEntity.ok(result);
    }

    /**
     * 根据配置ID发送WOL魔术包
     */
    @PostMapping("/wake/{id}")
    public ResponseEntity<Map<String, String>> wakeById(@PathVariable Long id) {
        String result = wolService.sendWolPacketById(id);
        return ResponseEntity.ok(Map.of("message", result));
    }

    /**
     * 根据IP地址发送WOL魔术包
     */
    @PostMapping("/wake/ip/{ip}")
    public ResponseEntity<Map<String, String>> wakeByIp(@PathVariable String ip) {
        String result = wolService.sendWolPacketByIp(ip);
        return ResponseEntity.ok(Map.of("message", result));
    }

    /**
     * 根据设备名称发送WOL魔术包
     */
    @PostMapping("/wake/name/{name}")
    public ResponseEntity<Map<String, String>> wakeByName(@PathVariable String name) {
        String result = wolService.sendWolPacketByName(name);
        return ResponseEntity.ok(Map.of("message", result));
    }

    /**
     * 刷新IP监控配置
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refreshConfigs() {
        ipMonitor.refreshConfigs();
        return ResponseEntity.ok(Map.of("message", "配置刷新成功"));
    }

    /**
     * 启动IP监控服务
     */
    @PostMapping("/monitor/start")
    public ResponseEntity<Map<String, String>> startMonitor() {
        ipMonitor.start();
        return ResponseEntity.ok(Map.of("message", "IP监控服务启动成功"));
    }

    /**
     * 停止IP监控服务
     */
    @PostMapping("/monitor/stop")
    public ResponseEntity<Map<String, String>> stopMonitor() {
        ipMonitor.stop();
        return ResponseEntity.ok(Map.of("message", "IP监控服务停止成功"));
    }

    // DTO转换方法
    private WolConfigDTO convertToDTO(WolConfig config) {
        WolConfigDTO dto = new WolConfigDTO();
        dto.setId(config.getId());
        dto.setName(config.getName());
        dto.setIpAddress(config.getIpAddress());
        dto.setSubnetMask(config.getSubnetMask());
        dto.setMacAddress(config.getMacAddress());
        dto.setWolPort(config.getWolPort());
        dto.setStatus(config.getStatus());
        dto.setEnabled(config.isEnabled());
        dto.setNotes(config.getNotes());
        dto.setCreatedAt(config.getCreatedAt());
        dto.setUpdatedAt(config.getUpdatedAt());
        
        // 添加在线状态
        Boolean online = ipMonitor.getIpStatus(config.getIpAddress());
        dto.setOnline(online);
        
        return dto;
    }

    private WolConfig convertFromCreateRequest(WolConfigCreateRequest request) {
        WolConfig config = new WolConfig();
        config.setName(request.getName());
        config.setIpAddress(request.getIpAddress());
        config.setSubnetMask(request.getSubnetMask());
        config.setMacAddress(request.getMacAddress());
        config.setWolPort(request.getWolPort());
        config.setNotes(request.getNotes());
        config.setStatus(1); // 默认启用
        return config;
    }

    private WolConfig convertFromUpdateRequest(WolConfigUpdateRequest request) {
        WolConfig config = new WolConfig();
        config.setName(request.getName());
        config.setIpAddress(request.getIpAddress());
        config.setSubnetMask(request.getSubnetMask());
        config.setMacAddress(request.getMacAddress());
        config.setWolPort(request.getWolPort());
        config.setStatus(request.getStatus());
        config.setNotes(request.getNotes());
        return config;
    }
}