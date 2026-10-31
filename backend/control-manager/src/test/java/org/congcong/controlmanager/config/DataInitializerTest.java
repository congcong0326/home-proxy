package org.congcong.controlmanager.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.congcong.controlmanager.service.OutboundService;
import org.congcong.controlmanager.service.RouteService;
import org.congcong.controlmanager.service.RuleSetService;
import org.congcong.controlmanager.service.UserService;
import org.congcong.controlmanager.service.InboundConfigService;
import org.congcong.controlmanager.repository.RouteRepository;
import org.congcong.controlmanager.repository.UserRepository;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.junit.jupiter.api.Test;

class DataInitializerTest {

    @Test
    void startupSeedsProxyDefaultsWithoutAdminCredentials() throws Exception {
        UserService userService = mock(UserService.class);
        RouteService routeService = mock(RouteService.class);
        OutboundService outboundService = mock(OutboundService.class);
        RuleSetService ruleSetService = mock(RuleSetService.class);
        InboundConfigService inboundConfigService = mock(InboundConfigService.class);
        RouteRepository routeRepository = mock(RouteRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ProxyWorkerRepository proxyWorkerRepository = mock(ProxyWorkerRepository.class);
        DataInitializer initializer = new DataInitializer(userService, routeService, outboundService, ruleSetService,
                inboundConfigService, routeRepository, userRepository, proxyWorkerRepository);

        initializer.run(null);

        verify(proxyWorkerRepository).save(org.mockito.ArgumentMatchers.argThat(w -> "default".equals(w.getWorkerId())));
        verify(userService).ensureDefaultAnonymousUserExists();
        verify(outboundService).ensureBuiltinOutboundsExist();
        verify(routeService).ensureDefaultRouteExists();
        verify(ruleSetService).ensureDefaultRuleSetsExist();
    }
}
