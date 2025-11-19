package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 聚合配置服务
 * 负责聚合所有启用的配置并生成统一的配置响应
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AggregateConfigService {

    private final InboundConfigService inboundConfigService;
    private final RateLimitService rateLimitService;
    private final RouteService routeService;
    private final UserService userService;


    /**
     * 获取聚合配置
     * 将所有启用的入站、路由、限流、用户配置聚合为一个响应
     * 
     * @return 聚合配置响应
     */
    public AggregateConfigResponse getAggregateConfig() {
        // 获取所有启用的配置
        List<InboundConfigDTO> inbounds = getEnabledInboundConfigs();
        List<RouteDTO> routes = getEnabledRoutes();
        List<RateLimitDTO> rateLimits = getEnabledRateLimits();
        List<UserDtoWithCredential> users = getEnabledUsers();
        
        // 计算配置哈希值
        String configHash = calculateConfigHash(inbounds, routes, rateLimits, users);
        // todo
        return AggregateConfigResponse.of(inbounds, routes, rateLimits, users, configHash);
    }

    /**
     * 用于ETag缓存机制，基于所有启用配置的内容生成
     * 
     * @return 配置内容的哈希值
     */
    public String getCurrentConfigHash() {
        // 获取所有启用的配置
        List<InboundConfigDTO> inbounds = getEnabledInboundConfigs();
        List<RouteDTO> routes = getEnabledRoutes();
        List<RateLimitDTO> rateLimits = getEnabledRateLimits();
        List<UserDtoWithCredential> users = getEnabledUsers();
        
        return calculateConfigHash(inbounds, routes, rateLimits, users);
    }


    /**
     * 获取所有启用的入站配置
     */
    private List<InboundConfigDTO> getEnabledInboundConfigs() {
        return inboundConfigService.getInboundConfigsByStatus(1);
    }

    /**
     * 获取所有启用的路由配置
     */
    private List<RouteDTO> getEnabledRoutes() {
        return routeService.getEnabledRoutes();

    }

    /**
     * 获取所有启用的限流配置
     */
    private List<RateLimitDTO> getEnabledRateLimits() {
        return rateLimitService.getRateLimitsByEnabled(true);
    }

    /**
     * 获取所有启用的用户配置
     */
    private List<UserDtoWithCredential> getEnabledUsers() {
        return userService.findUserDTOWithCredentialByStatus(1);
    }

    /**
     * 计算配置哈希值
     */
    private String calculateConfigHash(List<InboundConfigDTO> inbounds,
                                     List<RouteDTO> routes,
                                     List<RateLimitDTO> rateLimits,
                                     List<UserDtoWithCredential> users) {
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
                        .append(route.getRules())
                        .append(route.getPolicy())
                        .append(route.getOutboundTag())
                        .append(route.getOutboundProxyType())
                        .append(route.getOutboundProxyHost())
                        .append(route.getOutboundProxyPort())
                        .append(route.getOutboundProxyUsername())
                        .append(route.getOutboundProxyPassword())
                        .append(route.getStatus())
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

}