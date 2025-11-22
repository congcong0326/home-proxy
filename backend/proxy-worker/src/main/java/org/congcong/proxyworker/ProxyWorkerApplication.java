package org.congcong.proxyworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.*;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.audit.AccessLogUtil;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.ProxyContext;
import org.congcong.proxyworker.service.AggregateConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 代理工作节点主应用程序
 * 演示如何使用聚合配置服务
 */
@Slf4j
public class ProxyWorkerApplication {
    private static final ProxyContext PROXY_CONTEXT = ProxyContext.getInstance();
    
    public static void main(String[] args) {
        log.info("启动代理工作节点应用程序");
        
        // 创建配置服务
        AggregateConfigService configService = new AggregateConfigService();
        
        // 设置配置变更监听器
        configService.setConfigChangeListener(new ConfigChangeListener());
        // 日志服务启动
        AccessLogUtil.start();
        // 启动配置服务
        configService.start();
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("应用程序正在关闭...");
            configService.stop();
            PROXY_CONTEXT.closeAll();
            AccessLogUtil.stop();
        }));
        
        // 主线程保持运行
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            log.info("应用程序被中断");
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 配置变更监听器实现
     */
    private static class ConfigChangeListener implements AggregateConfigService.ConfigChangeListener {
        @Override
        public void onConfigChanged(AggregateConfigResponse newConfig) {
            log.info("收到配置变更通知:");
            log.info("  版本: {}", newConfig.getVersion());
            log.info("  生成时间: {}", newConfig.getGeneratedAt());
            log.info("  入站配置数量: {}", newConfig.getInbounds() != null ? newConfig.getInbounds().size() : 0);
            log.info("  路由配置数量: {}", newConfig.getRoutes() != null ? newConfig.getRoutes().size() : 0);
            log.info("  限流配置数量: {}", newConfig.getRateLimits() != null ? newConfig.getRateLimits().size() : 0);
            log.info("  用户配置数量: {}", newConfig.getUsers() != null ? newConfig.getUsers().size() : 0);
            log.info("  配置哈希: {}", newConfig.getConfigHash());
           
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            // 忽略来源对象中在目标类中不存在的字段（如 allowedUserIds、routeIds 等）
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            UserConfig anonymousUser = null;
            RouteConfig defaultRouteConfig = null;
            // 获取所有用户配置
            Map<Long, UserDtoWithCredential> userMap = new HashMap<>();
            Map<Long, RouteDTO> routeMap = new HashMap<>();
            if (newConfig.getUsers() != null) {
                for (UserDtoWithCredential user : newConfig.getUsers()) {
                    if (user != null && user.getId() != null) {
                        userMap.put(user.getId(), user);
                        if ("Anonymous user".equals(user.getUsername())) {
                            anonymousUser = mapper.convertValue(user, UserConfig.class);
                        }
                    }
                }
            }
            // 获取所有路由配置
            if (newConfig.getRoutes() != null) {
                for (RouteDTO route : newConfig.getRoutes()) {
                    if (route != null && route.getId() != null) {
                        routeMap.put(route.getId(), route);
                        if ("兜底直连路由规则".equals(route.getName())) {
                            defaultRouteConfig =  mapper.convertValue(route, RouteConfig.class);
                        }
                    }
                }
            }
            // 封装代理需要的配置
            List<InboundConfig> inboundConfigs = new ArrayList<>();
            if (newConfig.getInbounds() != null) {
                for (InboundConfigDTO inbound : newConfig.getInbounds()) {
                    if (inbound == null) {
                        continue;
                    }
                    // 拷贝了部分字段
                    InboundConfig inboundConfig = mapper.convertValue(inbound, InboundConfig.class);

                    // 关键的几个高效查询的字段
                    Map<String, UserConfig> userNameMap = new HashMap<>();
                    Map<String, UserConfig> deviceIpMapUser = new HashMap<>();
                    Map<Long, List<RouteConfig>> userToRoutesMap = new HashMap<>();

                    List<InboundRouteBinding> inboundRouteBindings = inbound.getInboundRouteBindings();
                    for (InboundRouteBinding inboundRouteBinding : inboundRouteBindings) {
                        List<Long> userIds = inboundRouteBinding.getUserIds();
                        List<Long> routeIds = inboundRouteBinding.getRouteIds();
                        List<RouteConfig> userBelongRoutes = new ArrayList<>();
                        for (Long routeId : routeIds) {
                            RouteDTO routeDTO = routeMap.get(routeId);
                            userBelongRoutes.add(mapper.convertValue(routeDTO, RouteConfig.class));
                        }
                        for (Long userId : userIds) {
                            userToRoutesMap.put(userId, userBelongRoutes);
                            UserDtoWithCredential userDtoWithCredential = userMap.get(userId);
                            UserConfig userConfig = mapper.convertValue(userDtoWithCredential, UserConfig.class);
                            userNameMap.put(userConfig.getUsername(), userConfig);
                            if (userConfig.getIpAddress() != null) {
                                deviceIpMapUser.put(userConfig.getIpAddress(), userConfig);
                            }
                        }
                    }

                    inboundConfig.setUsersMap(userNameMap);
                    inboundConfig.setDeviceIpMapUser(deviceIpMapUser);
                    inboundConfig.setRoutesMap(userToRoutesMap);
                    inboundConfig.setAnonymousUser(anonymousUser);
                    inboundConfig.setDefaultRouteConfig(defaultRouteConfig);
                    inboundConfigs.add(inboundConfig);
                }
            }
            log.info("已构建入站配置数量: {}", inboundConfigs.size());
            // 刷新代理服务
            PROXY_CONTEXT.refresh(inboundConfigs);
        }
    }
}