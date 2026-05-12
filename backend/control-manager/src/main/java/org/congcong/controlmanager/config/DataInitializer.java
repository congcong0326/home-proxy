package org.congcong.controlmanager.config;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.service.RouteService;
import org.congcong.controlmanager.service.UserService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 业务默认数据初始化器。
 */
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserService userService;

    private final RouteService routeService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        userService.ensureDefaultAnonymousUserExists();
        routeService.ensureDefaultRouteExists();
    }
}
