package org.congcong.controlmanager.config;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.service.RouteService;
import org.congcong.controlmanager.service.RuleSetService;
import org.congcong.controlmanager.service.UserService;
import org.congcong.controlmanager.service.InboundConfigService;
import org.congcong.controlmanager.repository.RouteRepository;
import org.congcong.controlmanager.repository.UserRepository;
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

    private final RuleSetService ruleSetService;
    private final InboundConfigService inboundConfigService;
    private final RouteRepository routeRepository;
    private final UserRepository userRepository;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        userService.ensureDefaultAnonymousUserExists();
        routeService.ensureDefaultRouteExists();
        ruleSetService.ensureDefaultRuleSetsExist();
        Long fallbackRouteId = routeRepository.findAll().stream()
                .filter(route -> "兜底直连路由规则".equals(route.getName()))
                .map(org.congcong.controlmanager.entity.Route::getId)
                .findFirst().orElse(null);
        Long anonymousUserId = userRepository.findByUsername("Anonymous user")
                .map(org.congcong.controlmanager.entity.User::getId)
                .orElse(null);
        if (fallbackRouteId != null && anonymousUserId != null) {
            inboundConfigService.backfillLegacyDefaultReferences(fallbackRouteId, anonymousUserId);
        }
    }
}
