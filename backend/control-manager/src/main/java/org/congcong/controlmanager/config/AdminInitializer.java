package org.congcong.controlmanager.config;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.service.AdminAuthService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 管理员初始化器
 * 在应用启动时检查并创建默认管理员账号
 */
@Component
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {
    
    private final AdminAuthService adminAuthService;
    
    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 在应用启动时检查并创建默认管理员
        adminAuthService.ensureDefaultAdminExists();
    }
}