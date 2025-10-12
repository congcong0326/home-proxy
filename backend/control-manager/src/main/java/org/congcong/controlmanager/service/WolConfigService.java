package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.config.WolConfig;
import org.congcong.controlmanager.event.WolConfigChangedEvent;
import org.congcong.controlmanager.repository.WolConfigRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WolConfigService {
    
    private final WolConfigRepository wolConfigRepository;
    private final ApplicationEventPublisher eventPublisher;
    
    /**
     * 获取所有WOL配置
     */
    public List<WolConfig> getAllConfigs() {
        return wolConfigRepository.findAll();
    }
    
    /**
     * 获取所有启用的WOL配置
     */
    public List<WolConfig> getAllEnabledConfigs() {
        return wolConfigRepository.findAllEnabled();
    }
    
    /**
     * 根据ID获取WOL配置
     */
    public Optional<WolConfig> getConfigById(Long id) {
        return wolConfigRepository.findById(id);
    }
    
    /**
     * 根据IP地址获取WOL配置
     */
    public Optional<WolConfig> getConfigByIpAddress(String ipAddress) {
        return wolConfigRepository.findByIpAddress(ipAddress);
    }
    
    /**
     * 根据MAC地址获取WOL配置
     */
    public Optional<WolConfig> getConfigByMacAddress(String macAddress) {
        return wolConfigRepository.findByMacAddress(macAddress);
    }
    
    /**
     * 根据设备名称获取WOL配置
     */
    public Optional<WolConfig> getConfigByName(String name) {
        return wolConfigRepository.findByName(name);
    }
    
    /**
     * 创建WOL配置
     */
    @Transactional
    public WolConfig createConfig(WolConfig config) {
        validateConfig(config, null);
        
        WolConfig savedConfig = wolConfigRepository.save(config);
        
        // 发布配置变更事件
        eventPublisher.publishEvent(new WolConfigChangedEvent(
                this, WolConfigChangedEvent.ChangeType.CREATED, 
                savedConfig.getId(), savedConfig.getName()));
        
        log.info("创建WOL配置成功: {}", savedConfig.getName());
        return savedConfig;
    }
    
    /**
     * 更新WOL配置
     */
    @Transactional
    public WolConfig updateConfig(Long id, WolConfig config) {
        WolConfig existingConfig = wolConfigRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "WOL配置不存在: " + id));
        
        validateConfig(config, id);
        
        // 更新字段
        existingConfig.setName(config.getName());
        existingConfig.setIpAddress(config.getIpAddress());
        existingConfig.setSubnetMask(config.getSubnetMask());
        existingConfig.setMacAddress(config.getMacAddress());
        existingConfig.setWolPort(config.getWolPort());
        existingConfig.setStatus(config.getStatus());
        existingConfig.setNotes(config.getNotes());
        
        WolConfig savedConfig = wolConfigRepository.save(existingConfig);
        
        // 发布配置变更事件
        eventPublisher.publishEvent(new WolConfigChangedEvent(
                this, WolConfigChangedEvent.ChangeType.UPDATED, 
                savedConfig.getId(), savedConfig.getName()));
        
        log.info("更新WOL配置成功: {}", savedConfig.getName());
        return savedConfig;
    }
    
    /**
     * 删除WOL配置
     */
    @Transactional
    public void deleteConfig(Long id) {
        WolConfig config = wolConfigRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "WOL配置不存在: " + id));
        
        String configName = config.getName();
        wolConfigRepository.delete(config);
        
        // 发布配置变更事件
        eventPublisher.publishEvent(new WolConfigChangedEvent(
                this, WolConfigChangedEvent.ChangeType.DELETED, 
                id, configName));
        
        log.info("删除WOL配置成功: {}", configName);
    }
    
    /**
     * 启用WOL配置
     */
    @Transactional
    public WolConfig enableConfig(Long id) {
        WolConfig config = wolConfigRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "WOL配置不存在: " + id));
        
        config.enable();
        WolConfig savedConfig = wolConfigRepository.save(config);
        
        // 发布配置变更事件
        eventPublisher.publishEvent(new WolConfigChangedEvent(
                this, WolConfigChangedEvent.ChangeType.ENABLED, 
                savedConfig.getId(), savedConfig.getName()));
        
        log.info("启用WOL配置成功: {}", savedConfig.getName());
        return savedConfig;
    }
    
    /**
     * 禁用WOL配置
     */
    @Transactional
    public WolConfig disableConfig(Long id) {
        WolConfig config = wolConfigRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "WOL配置不存在: " + id));
        
        config.disable();
        WolConfig savedConfig = wolConfigRepository.save(config);
        
        // 发布配置变更事件
        eventPublisher.publishEvent(new WolConfigChangedEvent(
                this, WolConfigChangedEvent.ChangeType.DISABLED, 
                savedConfig.getId(), savedConfig.getName()));
        
        log.info("禁用WOL配置成功: {}", savedConfig.getName());
        return savedConfig;
    }
    
    /**
     * 验证WOL配置
     */
    private void validateConfig(WolConfig config, Long excludeId) {
        if (config.getName() == null || config.getName().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备名称不能为空");
        }
        
        if (config.getIpAddress() == null || config.getIpAddress().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IP地址不能为空");
        }
        
        if (config.getMacAddress() == null || config.getMacAddress().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MAC地址不能为空");
        }
        
        // 验证MAC地址格式
        if (!isValidMacAddress(config.getMacAddress())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MAC地址格式不正确");
        }
        
        // 检查重复
        if (excludeId != null) {
            if (wolConfigRepository.existsByNameAndIdNot(config.getName(), excludeId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备名称已存在");
            }
            if (wolConfigRepository.existsByIpAddressAndIdNot(config.getIpAddress(), excludeId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IP地址已存在");
            }
            if (wolConfigRepository.existsByMacAddressAndIdNot(config.getMacAddress(), excludeId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MAC地址已存在");
            }
        } else {
            if (wolConfigRepository.existsByName(config.getName())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备名称已存在");
            }
            if (wolConfigRepository.existsByIpAddress(config.getIpAddress())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IP地址已存在");
            }
            if (wolConfigRepository.existsByMacAddress(config.getMacAddress())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MAC地址已存在");
            }
        }
    }
    
    /**
     * 验证MAC地址格式
     */
    private boolean isValidMacAddress(String macAddress) {
        if (macAddress == null) {
            return false;
        }
        // 支持 XX:XX:XX:XX:XX:XX 或 XX-XX-XX-XX-XX-XX 格式
        String pattern = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$";
        return macAddress.matches(pattern);
    }
}