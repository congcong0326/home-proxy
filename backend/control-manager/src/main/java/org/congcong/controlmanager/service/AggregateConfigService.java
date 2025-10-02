package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.*;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.RateLimit;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.entity.User;
import org.congcong.controlmanager.repository.InboundConfigRepository;
import org.congcong.controlmanager.repository.RateLimitRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.congcong.controlmanager.repository.UserRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 聚合配置服务
 * 负责聚合所有启用的配置并生成统一的配置响应
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AggregateConfigService {

    private final InboundConfigRepository inboundConfigRepository;
    private final RouteRepository routeRepository;
    private final RateLimitRepository rateLimitRepository;
    private final UserRepository userRepository;

    /**
     * 获取聚合配置
     * 将所有启用的入站、路由、限流、用户配置聚合为一个响应
     * 
     * @return 聚合配置响应
     */
    @Cacheable(value = "aggregateConfig", key = "'config'")
    public AggregateConfigResponse getAggregateConfig() {
        log.debug("开始聚合配置数据");
        
        // 获取所有启用的配置
        List<InboundConfigDto> inbounds = getEnabledInboundConfigs();
        List<RouteDto> routes = getEnabledRoutes();
        List<RateLimitDto> rateLimits = getEnabledRateLimits();
        List<UserDto> users = getEnabledUsers();
        
        // 计算配置哈希值
        String configHash = calculateConfigHash(inbounds, routes, rateLimits, users);
        
        log.debug("聚合配置完成，包含 {} 个入站配置，{} 个路由，{} 个限流策略，{} 个用户", 
                inbounds.size(), routes.size(), rateLimits.size(), users.size());
        
        return AggregateConfigResponse.of(inbounds, routes, rateLimits, users, configHash);
    }

    /**
     * 获取当前配置的哈希值
     * 用于ETag缓存机制，基于所有启用配置的内容生成
     * 
     * @return 配置内容的哈希值
     */
    @Cacheable(value = "configHash", key = "'hash'")
    public String getCurrentConfigHash() {
        log.debug("计算当前配置哈希值");
        
        // 获取所有启用的配置
        List<InboundConfigDto> inbounds = getEnabledInboundConfigs();
        List<RouteDto> routes = getEnabledRoutes();
        List<RateLimitDto> rateLimits = getEnabledRateLimits();
        List<UserDto> users = getEnabledUsers();
        
        return calculateConfigHash(inbounds, routes, rateLimits, users);
    }

    /**
     * 刷新配置缓存
     * 当配置发生变更时调用，重新计算配置哈希值
     */
    @CacheEvict(value = {"aggregateConfig", "configHash"}, allEntries = true)
    public void refreshConfigCache() {
        log.debug("刷新配置缓存");
    }

    /**
     * 获取所有启用的入站配置
     */
    private List<InboundConfigDto> getEnabledInboundConfigs() {
        List<InboundConfig> inboundConfigs = inboundConfigRepository.findByStatus(1);
        return inboundConfigs.stream()
                .map(this::convertInboundConfigToDto)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有启用的路由配置
     */
    private List<RouteDto> getEnabledRoutes() {
        List<Route> routes = routeRepository.findByStatus(1);
        return routes.stream()
                .map(this::convertRouteToDto)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有启用的限流配置
     */
    private List<RateLimitDto> getEnabledRateLimits() {
        List<RateLimit> rateLimits = rateLimitRepository.findByEnabled(true);
        return rateLimits.stream()
                .map(this::convertRateLimitToDto)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有启用的用户配置
     */
    private List<UserDto> getEnabledUsers() {
        List<User> users = userRepository.findByStatus(1);
        return users.stream()
                .map(this::convertUserToDto)
                .collect(Collectors.toList());
    }

    /**
     * 计算配置哈希值
     */
    private String calculateConfigHash(List<InboundConfigDto> inbounds,
                                     List<RouteDto> routes,
                                     List<RateLimitDto> rateLimits,
                                     List<UserDto> users) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            // 将所有配置内容拼接成字符串
            StringBuilder configContent = new StringBuilder();
            
            // 添加入站配置
            inbounds.forEach(inbound -> {
                configContent.append("inbound:")
                        .append(inbound.getId())
                        .append(inbound.getName())
                        .append(inbound.getProtocol())
                        .append(inbound.getListenIp())
                        .append(inbound.getPort())
                        .append(inbound.getTlsEnabled())
                        .append(inbound.getSniffEnabled())
                        .append(inbound.getSsMethod())
                        .append(inbound.getAllowedUserIds())
                        .append(inbound.getRouteIds())
                        .append(inbound.getUpdatedAt());
            });
            
            // 添加路由配置
            routes.forEach(route -> {
                configContent.append("route:")
                        .append(route.getId())
                        .append(route.getName())
                        .append(route.getRulesJson())
                        .append(route.getPolicy())
                        .append(route.getOutboundTag())
                        .append(route.getOutboundProxyType())
                        .append(route.getOutboundProxyHost())
                        .append(route.getOutboundProxyPort())
                        .append(route.getOutboundProxyUsername())
                        .append(route.getOutboundProxyPassword())
                        .append(route.getOutboundProxyTls())
                        .append(route.getUpdatedAt());
            });
            
            // 添加限流配置
            rateLimits.forEach(rateLimit -> {
                configContent.append("rateLimit:")
                        .append(rateLimit.getId())
                        .append(rateLimit.getScopeType())
                        .append(rateLimit.getUserIds())
                        .append(rateLimit.getUplinkLimitBps())
                        .append(rateLimit.getDownlinkLimitBps())
                        .append(rateLimit.getBurstBytes())
                        .append(rateLimit.getEnabled())
                        .append(rateLimit.getEffectiveTimeStart())
                        .append(rateLimit.getEffectiveTimeEnd())
                        .append(rateLimit.getEffectiveFrom())
                        .append(rateLimit.getEffectiveTo())
                        .append(rateLimit.getUpdatedAt());
            });
            
            // 添加用户配置
            users.forEach(user -> {
                configContent.append("user:")
                        .append(user.getId())
                        .append(user.getUsername())
                        .append(user.getCredential())
                        .append(user.getStatus())
                        .append(user.getUpdatedAt());
            });
            
            byte[] hashBytes = digest.digest(configContent.toString().getBytes(StandardCharsets.UTF_8));
            
            // 转换为十六进制字符串
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
            
        } catch (NoSuchAlgorithmException e) {
            log.error("计算配置哈希值失败", e);
            // 如果哈希计算失败，使用时间戳作为备选方案
            return String.valueOf(System.currentTimeMillis());
        }
    }

    /**
     * 转换入站配置实体为DTO
     */
    private InboundConfigDto convertInboundConfigToDto(InboundConfig entity) {
        InboundConfigDto dto = new InboundConfigDto();
        
        // 手动映射所有字段以确保类型转换正确
        dto.setId(String.valueOf(entity.getId()));
        dto.setName(entity.getName());
        dto.setProtocol(entity.getProtocol() != null ? entity.getProtocol().getValue() : null);
        dto.setListenIp(entity.getListenIp());
        dto.setPort(entity.getPort());
        dto.setTlsEnabled(entity.getTlsEnabled());
        dto.setSniffEnabled(entity.getSniffEnabled());
        dto.setSsMethod(entity.getSsMethod());
        dto.setAllowedUserIds(entity.getAllowedUserIds());
        dto.setRouteIds(entity.getRouteIds());
        dto.setStatus(entity.getStatus());
        dto.setNotes(entity.getNotes());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        
        return dto;
    }

    /**
     * 转换路由实体为DTO
     */
    private RouteDto convertRouteToDto(Route entity) {
        RouteDto dto = new RouteDto();
        
        // 手动映射所有字段以确保类型转换正确
        dto.setId(String.valueOf(entity.getId()));
        dto.setName(entity.getName());
        // 将RouteRule列表转换为JSON字符串（这里需要根据实际需求处理）
        dto.setRulesJson(entity.getRules() != null ? entity.getRules().toString() : null);
        dto.setPolicy(entity.getPolicy() != null ? entity.getPolicy().getValue() : null);
        dto.setOutboundTag(entity.getOutboundTag());
        dto.setOutboundProxyType(entity.getOutboundProxyType() != null ? entity.getOutboundProxyType().getValue() : null);
        dto.setOutboundProxyHost(entity.getOutboundProxyHost());
        dto.setOutboundProxyPort(entity.getOutboundProxyPort());
        dto.setOutboundProxyUsername(entity.getOutboundProxyUsername());
        dto.setOutboundProxyPassword(entity.getOutboundProxyPassword());
        dto.setStatus(entity.getStatus());
        dto.setNotes(entity.getNotes());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        
        return dto;
    }

    /**
     * 转换限流实体为DTO
     */
    private RateLimitDto convertRateLimitToDto(RateLimit entity) {
        RateLimitDto dto = new RateLimitDto();
        
        // 手动映射所有字段以确保类型转换正确
        dto.setId(String.valueOf(entity.getId()));
        dto.setScopeType(entity.getScopeType() != null ? entity.getScopeType().getValue() : null);
        // 将List<Long>转换为List<String>
        dto.setUserIds(entity.getUserIds() != null ? 
            entity.getUserIds().stream().map(String::valueOf).collect(java.util.stream.Collectors.toList()) : null);
        dto.setUplinkLimitBps(entity.getUplinkLimitBps());
        dto.setDownlinkLimitBps(entity.getDownlinkLimitBps());
        dto.setBurstBytes(entity.getBurstBytes());
        dto.setEnabled(entity.getEnabled());
        dto.setEffectiveTimeStart(entity.getEffectiveTimeStart());
        dto.setEffectiveTimeEnd(entity.getEffectiveTimeEnd());
        dto.setEffectiveFrom(entity.getEffectiveFrom());
        dto.setEffectiveTo(entity.getEffectiveTo());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        
        return dto;
    }

    /**
     * 转换用户实体为DTO
     */
    private UserDto convertUserToDto(User entity) {
        UserDto dto = new UserDto();
        
        // 手动映射所有字段以确保类型转换正确
        dto.setId(String.valueOf(entity.getId()));
        dto.setUsername(entity.getUsername());
        dto.setCredential(entity.getCredential());
        dto.setStatus(entity.getStatus());
        dto.setRemark(entity.getRemark());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        
        return dto;
    }
}