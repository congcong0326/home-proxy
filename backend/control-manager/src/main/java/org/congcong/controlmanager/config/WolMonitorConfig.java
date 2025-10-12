package org.congcong.controlmanager.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.service.IpMonitor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;

/**
 * WOL监控配置类
 * 应用启动时自动启动IP监控服务
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WolMonitorConfig implements ApplicationRunner {

    private final IpMonitor ipMonitor;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("应用启动完成，开始启动WOL IP监控服务");
        try {
            ipMonitor.start();
            log.info("WOL IP监控服务启动成功");
        } catch (Exception e) {
            log.error("启动WOL IP监控服务失败", e);
        }
    }
}