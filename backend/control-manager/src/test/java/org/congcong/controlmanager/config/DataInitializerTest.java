package org.congcong.controlmanager.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.congcong.controlmanager.service.RouteService;
import org.congcong.controlmanager.service.RuleSetService;
import org.congcong.controlmanager.service.UserService;
import org.junit.jupiter.api.Test;

class DataInitializerTest {

    @Test
    void startupSeedsProxyDefaultsWithoutAdminCredentials() throws Exception {
        UserService userService = mock(UserService.class);
        RouteService routeService = mock(RouteService.class);
        RuleSetService ruleSetService = mock(RuleSetService.class);
        DataInitializer initializer = new DataInitializer(userService, routeService, ruleSetService);

        initializer.run(null);

        verify(userService).ensureDefaultAnonymousUserExists();
        verify(routeService).ensureDefaultRouteExists();
        verify(ruleSetService).ensureDefaultRuleSetsExist();
    }
}
